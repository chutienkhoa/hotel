package com.hotel.reservation;

import com.hotel.audit.*;
import com.hotel.guest.*;
import com.hotel.room.*;
import com.hotel.security.*;
import jakarta.transaction.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Điều phối các thao tác nghiệp vụ và transaction của reservation. */
@Service
public class ReservationService {
  private final ReservationRepository reservations;
  private final GuestRepository guests;
  private final RoomRepository rooms;
  private final StayRepository stays;
  private final AuditLogRepository audits;

  /**
   * Tạo dịch vụ với các repository phụ thuộc.
   *
   * @param reservations repository reservation
   * @param guests repository khách
   * @param rooms repository phòng
   * @param stays repository lưu trú
   * @param audits repository audit
   */
  ReservationService(
      ReservationRepository reservations,
      GuestRepository guests,
      RoomRepository rooms,
      StayRepository stays,
      AuditLogRepository audits) {
    this.reservations = reservations;
    this.guests = guests;
    this.rooms = rooms;
    this.stays = stays;
    this.audits = audits;
  }

  @Transactional
  /**
   * Tạo một reservation nháp hoàn chỉnh từ request.
   *
   * @param request dữ liệu đặt phòng
   * @return reservation vừa tạo
   */
  public ReservationDtos.Response create(ReservationDtos.CreateRequest request) {
    if (!request.checkOutDate().isAfter(request.checkInDate()))
      throw bad("check_out_date must be after check_in_date");
    Currency currency;
    try {
      currency = Currency.getInstance(request.currency());
    } catch (IllegalArgumentException e) {
      throw bad("Unsupported currency");
    }
    Guest guest = guests.findById(request.guestId()).orElseThrow(() -> notFound("Guest"));
    Set<UUID> ids = new HashSet<>();
    for (var r : request.rooms())
      if (!ids.add(r.roomId())) throw bad("A room may be assigned once per reservation");
    Map<UUID, Room> available =
        rooms.findAllById(ids).stream()
            .collect(java.util.stream.Collectors.toMap(Room::getId, r -> r));
    if (available.size() != ids.size()) throw notFound("Room");
    CurrentUser user = currentUser();
    Reservation reservation =
        new Reservation(
            UUID.randomUUID(),
            guest,
            request.checkInDate(),
            request.checkOutDate(),
            currency.getCurrencyCode(),
            request.notes());
    reservation.audit(user.id());
    for (var input : request.rooms()) {
      BigDecimal rate = scale(input.nightlyRate(), currency);
      ReservationRoom line =
          new ReservationRoom(
              reservation,
              available.get(input.roomId()),
              request.checkInDate(),
              request.checkOutDate(),
              rate);
      line.audit(user.id());
      reservation.addRoom(line);
    }
    reservation.calculateTotal();
    reservations.save(reservation);
    audit(user, "CREATE", reservation, null, "DRAFT");
    return response(reservation);
  }

  @Transactional
  /**
   * Xác nhận reservation sau khi khóa phòng và kiểm tra xung đột.
   *
   * @param id định danh reservation
   * @return reservation sau khi xác nhận
   */
  public ReservationDtos.Response confirm(UUID id) {
    Reservation r = load(id);
    CurrentUser user = currentUser();
    if (r.getStatus() != ReservationStatus.DRAFT)
      throw conflict("Invalid reservation state transition");
    List<UUID> ids = r.getRooms().stream().map(x -> x.getRoom().getId()).sorted().toList();
    List<Room> locked = rooms.lockAllByIdIn(ids);
    if (locked.size() != ids.size()) throw notFound("Room");
    for (ReservationRoom line : r.getRooms())
      if (reservations.hasOverlap(
          line.getRoom().getId(),
          r.getCheckInDate(),
          r.getCheckOutDate(),
          List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)))
        throw conflict("Room is already booked for these dates");
    r.confirm();
    r.audit(user.id());
    audit(user, "CONFIRM", r, "DRAFT", "CONFIRMED");
    return response(r);
  }

  @Transactional
  /**
   * Hủy reservation đã xác nhận.
   *
   * @param id định danh reservation
   * @return reservation sau khi hủy
   */
  public ReservationDtos.Response cancel(UUID id) {
    Reservation r = load(id);
    CurrentUser u = currentUser();
    ReservationStatus old = r.getStatus();
    try {
      r.cancel();
    } catch (IllegalStateException e) {
      throw conflict(e.getMessage());
    }
    r.audit(u.id());
    audit(u, "CANCEL", r, old.name(), r.getStatus().name());
    return response(r);
  }

  @Transactional
  /**
   * Đánh dấu reservation đã xác nhận là no-show.
   *
   * @param id định danh reservation
   * @return reservation sau khi cập nhật
   */
  public ReservationDtos.Response noShow(UUID id) {
    Reservation r = load(id);
    CurrentUser u = currentUser();
    ReservationStatus old = r.getStatus();
    try {
      r.noShow();
    } catch (IllegalStateException e) {
      throw conflict(e.getMessage());
    }
    r.audit(u.id());
    audit(u, "NO_SHOW", r, old.name(), r.getStatus().name());
    return response(r);
  }

  @Transactional
  /**
   * Check-in reservation, tạo Stay và chuyển phòng sang OCCUPIED.
   *
   * @param id định danh reservation
   * @return reservation sau khi check-in
   */
  public ReservationDtos.Response checkIn(UUID id) {
    Reservation r = load(id);
    CurrentUser u = currentUser();
    if (r.getStatus() != ReservationStatus.CONFIRMED)
      throw conflict("Invalid reservation state transition");
    if (stays.existsByReservationId(id)) throw conflict("Stay already exists");
    List<UUID> ids = r.getRooms().stream().map(x -> x.getRoom().getId()).sorted().toList();
    List<Room> locked = rooms.lockAllByIdIn(ids);
    if (locked.size() != ids.size()) throw notFound("Room");
    for (Room room : locked)
      if (!room.isActive() || room.getStatus() != RoomStatus.AVAILABLE)
        throw conflict("Room is not available for check-in");
    for (Room room : locked) {
      room.occupy();
      room.audit(u.id());
    }
    r.checkIn();
    r.audit(u.id());
    Stay stay = new Stay(r);
    stay.audit(u.id());
    stays.save(stay);
    audit(u, "CHECK_IN", r, "CONFIRMED", "CHECKED_IN");
    return response(r);
  }

  /**
   * Tải reservation theo định danh.
   *
   * @param id định danh reservation
   * @return reservation được tìm thấy
   * @throws ResponseStatusException nếu reservation không tồn tại
   */
  private Reservation load(UUID id) {
    return reservations.findById(id).orElseThrow(() -> notFound("Reservation"));
  }

  /**
   * Lấy người dùng đã được xác thực từ security context.
   *
   * @return người dùng hiện tại
   * @throws ResponseStatusException nếu không có principal hợp lệ
   */
  private CurrentUser currentUser() {
    Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    if (p instanceof CurrentUser u) return u;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  /**
   * Ghi một sự kiện audit cho reservation.
   *
   * @param u người thực hiện
   * @param action thao tác được ghi nhận
   * @param r reservation liên quan
   * @param oldValue giá trị trước thao tác
   * @param newValue giá trị sau thao tác
   */
  private void audit(
      CurrentUser u, String action, Reservation r, String oldValue, String newValue) {
    audits.save(new AuditLog(u.id(), action, r.getId(), oldValue, newValue));
  }

  /**
   * Chuyển thực thể reservation thành DTO phản hồi.
   *
   * @param r reservation nguồn
   * @return DTO phản hồi
   */
  private ReservationDtos.Response response(Reservation r) {
    return new ReservationDtos.Response(
        r.getId(),
        r.getReservationNumber(),
        r.getStatus().name(),
        r.getTotalAmount(),
        r.getCurrency());
  }

  /**
   * Chuẩn hóa giá theo số chữ số thập phân của tiền tệ.
   *
   * @param value giá cần chuẩn hóa
   * @param currency tiền tệ áp dụng
   * @return giá đã chuẩn hóa
   * @throws ResponseStatusException nếu giá vượt độ chính xác tiền tệ
   */
  private BigDecimal scale(BigDecimal value, Currency currency) {
    try {
      return value.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad("Rate exceeds currency precision");
    }
  }

  /**
   * Tạo lỗi HTTP 400.
   *
   * @param m thông điệp lỗi
   * @return ngoại lệ phản hồi lỗi dữ liệu
   */
  private ResponseStatusException bad(String m) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
  }

  /**
   * Tạo lỗi HTTP 404.
   *
   * @param m tên tài nguyên không tìm thấy
   * @return ngoại lệ phản hồi không tìm thấy
   */
  private ResponseStatusException notFound(String m) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, m + " not found");
  }

  /**
   * Tạo lỗi HTTP 409.
   *
   * @param m thông điệp xung đột
   * @return ngoại lệ phản hồi xung đột
   */
  private ResponseStatusException conflict(String m) {
    return new ResponseStatusException(HttpStatus.CONFLICT, m);
  }
}
