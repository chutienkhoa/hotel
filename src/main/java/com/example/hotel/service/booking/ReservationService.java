package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final ReservationMapper reservationMapper;
    private final ReservationNumberGenerator reservationNumberGenerator;
    private final StayBalanceService stayBalanceService;

    /**
     * Tạo dịch vụ với các repository phụ thuộc.
     *
     * @param reservations repository reservation
     * @param guests repository khách
     * @param rooms repository phòng
     * @param stays repository lưu trú
     * @param audits repository audit
     * @param reservationMapper mapper chuyển đổi reservation thành DTO phản hồi
     * @param reservationNumberGenerator generator tạo reservation number hằng ngày
     * @param stayBalanceService service tính số dư của Stay khi check-out
     */
    ReservationService(
            ReservationRepository reservations,
            GuestRepository guests,
            RoomRepository rooms,
            StayRepository stays,
            AuditLogRepository audits,
            ReservationMapper reservationMapper,
            ReservationNumberGenerator reservationNumberGenerator,
            StayBalanceService stayBalanceService) {
        this.reservations = reservations;
        this.guests = guests;
        this.rooms = rooms;
        this.stays = stays;
        this.audits = audits;
        this.reservationMapper = reservationMapper;
        this.reservationNumberGenerator = reservationNumberGenerator;
        this.stayBalanceService = stayBalanceService;
    }

    /**
     * Tạo một reservation nháp hoàn chỉnh từ request.
     *
     * @param request dữ liệu đặt phòng
     * @return reservation vừa tạo
     */
    @Transactional
    public Response create(CreateRequest request) {
        if (!request.checkOutDate().isAfter(request.checkInDate())) {
            throw bad("check_out_date must be after check_in_date");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(request.currency());
        } catch (IllegalArgumentException exception) {
            throw bad("Unsupported currency");
        }
        Guest guest = guests.findById(request.guestId()).orElseThrow(() -> notFound("Guest"));
        Set<UUID> roomIds = new HashSet<>();
        for (var roomRequest : request.rooms()) {
            if (!roomIds.add(roomRequest.roomId())) {
                throw bad("A room may be assigned once per reservation");
            }
        }
        Map<UUID, Room> roomsById =
                rooms.findAllById(roomIds).stream()
                        .collect(Collectors.toMap(Room::getId, room -> room));
        if (roomsById.size() != roomIds.size()) {
            throw notFound("Room");
        }
        CurrentUser user = currentUser();
        Reservation reservation =
                new Reservation(
                        UUID.randomUUID(),
                        reservationNumberGenerator.generate(),
                        guest,
                        request.checkInDate(),
                        request.checkOutDate(),
                        currency.getCurrencyCode(),
                        request.notes());
        reservation.audit(user.id());
        for (var roomRequest : request.rooms()) {
            BigDecimal nightlyRate = scale(roomRequest.nightlyRate(), currency);
            ReservationRoom reservationRoom =
                    new ReservationRoom(
                            reservation,
                            roomsById.get(roomRequest.roomId()),
                            request.checkInDate(),
                            request.checkOutDate(),
                            nightlyRate);
            reservationRoom.audit(user.id());
            reservation.addRoom(reservationRoom);
        }
        reservation.calculateTotal();
        reservations.save(reservation);
        audit(user, "CREATE", reservation, null, "DRAFT");
        return response(reservation);
    }

    /**
     * Xác nhận reservation sau khi khóa phòng và kiểm tra xung đột.
     *
     * @param id định danh reservation
     * @return reservation sau khi xác nhận
     */
    @Transactional
    public Response confirm(UUID id) {
        Reservation reservation = load(id);
        CurrentUser user = currentUser();
        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw conflict("Invalid reservation state transition");
        }
        List<UUID> roomIds = reservation.getRooms().stream()
                .map(reservationRoom -> reservationRoom.getRoom().getId())
                .sorted()
                .toList();
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw notFound("Room");
        }
        for (ReservationRoom reservationRoom : reservation.getRooms()) {
            if (reservations.hasOverlap(
                    reservationRoom.getRoom().getId(),
                    reservation.getCheckInDate(),
                    reservation.getCheckOutDate(),
                    List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN))) {
                throw conflict("Room is already booked for these dates");
            }
        }
        reservation.confirm();
        reservation.audit(user.id());
        audit(user, "CONFIRM", reservation, "DRAFT", "CONFIRMED");
        return response(reservation);
    }

    /**
     * Hủy reservation đã xác nhận.
     *
     * @param id định danh reservation
     * @return reservation sau khi hủy
     */
    @Transactional
    public Response cancel(UUID id) {
        Reservation reservation = load(id);
        CurrentUser user = currentUser();
        ReservationStatus previousStatus = reservation.getStatus();
        try {
            reservation.cancel();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        reservation.audit(user.id());
        audit(user, "CANCEL", reservation, previousStatus.name(), reservation.getStatus().name());
        return response(reservation);
    }

    /**
     * Đánh dấu reservation đã xác nhận là no-show.
     *
     * @param id định danh reservation
     * @return reservation sau khi cập nhật
     */
    @Transactional
    public Response noShow(UUID id) {
        Reservation reservation = load(id);
        CurrentUser user = currentUser();
        ReservationStatus previousStatus = reservation.getStatus();
        try {
            reservation.noShow();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        reservation.audit(user.id());
        audit(user, "NO_SHOW", reservation, previousStatus.name(), reservation.getStatus().name());
        return response(reservation);
    }

    /**
     * Check-in reservation, tạo Stay và chuyển phòng sang OCCUPIED.
     *
     * @param id định danh reservation
     * @return reservation sau khi check-in
     */
    @Transactional
    public Response checkIn(UUID id) {
        Reservation reservation = load(id);
        CurrentUser user = currentUser();
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw conflict("Invalid reservation state transition");
        }
        if (stays.existsByReservationId(id)) {
            throw conflict("Stay already exists");
        }
        List<UUID> roomIds = reservation.getRooms().stream()
                .map(reservationRoom -> reservationRoom.getRoom().getId())
                .sorted()
                .toList();
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw notFound("Room");
        }
        for (Room room : lockedRooms) {
            if (!room.isActive() || room.getStatus() != RoomStatus.AVAILABLE) {
                throw conflict("Room is not available for check-in");
            }
        }
        for (Room room : lockedRooms) {
            room.occupy();
            room.audit(user.id());
        }
        reservation.checkIn();
        reservation.audit(user.id());
        Stay stay = new Stay(reservation);
        stay.audit(user.id());
        stays.save(stay);
        audit(user, "CHECK_IN", reservation, "CONFIRMED", "CHECKED_IN");
        return response(reservation);
    }

    /**
     * Checks out every room assigned to a checked-in reservation in one transaction.
     *
     * @param id reservation identifier
     * @return reservation after its completed check-out
     * @throws ResponseStatusException if the reservation, Stay, balance, or Room states prevent check-out
     */
    @Transactional
    public Response checkOut(UUID id) {
        Reservation reservation = load(id);
        CurrentUser user = currentUser();
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw conflict("Invalid reservation state transition");
        }
        Stay stay = stays
                .findByReservationIdForUpdate(id)
                .orElseThrow(() -> conflict("Stay not found for reservation"));
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Invalid stay state transition");
        }
        StayBalance balance = stayBalanceService.calculate(stay.getId());
        if (balance.outstanding().compareTo(BigDecimal.ZERO) != 0) {
            throw conflict("Outstanding balance must be zero for check-out");
        }
        List<UUID> roomIds = reservation.getRooms().stream()
                .map(reservationRoom -> reservationRoom.getRoom().getId())
                .sorted()
                .toList();
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw notFound("Room");
        }
        for (Room room : lockedRooms) {
            if (room.getStatus() != RoomStatus.OCCUPIED) {
                throw conflict("Room is not occupied for check-out");
            }
        }
        try {
            for (Room room : lockedRooms) {
                room.markDirty();
                room.audit(user.id());
            }
            reservation.checkOut();
            reservation.audit(user.id());
            stay.checkOut();
            stay.audit(user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        audit(user, "CHECK_OUT", reservation, "CHECKED_IN", "CHECKED_OUT");
        return response(reservation);
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
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.username());
        }
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
            CurrentUser user,
            String action,
            Reservation reservation,
            String oldValue,
            String newValue) {
        audits.save(new AuditLog(user.id(), action, reservation.getId(), oldValue, newValue));
    }

    /**
     * Chuyển thực thể reservation thành DTO phản hồi.
     *
     * @param r reservation nguồn
     * @return DTO phản hồi
     */
    private Response response(Reservation reservation) {
        return reservationMapper.toResponse(reservation);
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
        } catch (ArithmeticException exception) {
            throw bad("Rate exceeds currency precision");
        }
    }

    /**
     * Tạo lỗi HTTP 400.
     *
     * @param message thông điệp lỗi
     * @return ngoại lệ phản hồi lỗi dữ liệu
     */
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Tạo lỗi HTTP 404.
     *
     * @param resourceName tên tài nguyên không tìm thấy
     * @return ngoại lệ phản hồi không tìm thấy
     */
    private ResponseStatusException notFound(String resourceName) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
    }

    /**
     * Tạo lỗi HTTP 409.
     *
     * @param message thông điệp xung đột
     * @return ngoại lệ phản hồi xung đột
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
