package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.exception.GuestCompositionUpdateException;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private final StayRoomAssignmentRepository stayRoomAssignments;
    private final ChargeRepository charges;
    private final AuditLogRepository audits;
    private final ReservationMapper reservationMapper;
    private final ReservationNumberGenerator reservationNumberGenerator;
    private final StayBalanceService stayBalanceService;
    private final Clock clock;

    /**
     * Tạo dịch vụ với các repository phụ thuộc.
     *
     * @param reservations repository reservation
     * @param guests repository khách
     * @param rooms repository phòng
     * @param stays repository lưu trú
     * @param stayRoomAssignments repository lịch sử chiếm phòng thực tế, seeded khi check-in và
     *     đóng lại khi check-out
     * @param charges repository charge, dùng để tạo ROOM Charge tự động khi check-in
     * @param audits repository audit
     * @param reservationMapper mapper chuyển đổi reservation thành DTO phản hồi
     * @param reservationNumberGenerator generator tạo reservation number hằng ngày
     * @param stayBalanceService service tính số dư của Stay khi check-out
     * @param clock authoritative hotel business clock used for Check-in date rules
     */
    ReservationService(
            ReservationRepository reservations,
            GuestRepository guests,
            RoomRepository rooms,
            StayRepository stays,
            StayRoomAssignmentRepository stayRoomAssignments,
            ChargeRepository charges,
            AuditLogRepository audits,
            ReservationMapper reservationMapper,
            ReservationNumberGenerator reservationNumberGenerator,
            StayBalanceService stayBalanceService,
            Clock clock) {
        this.reservations = reservations;
        this.guests = guests;
        this.rooms = rooms;
        this.stays = stays;
        this.stayRoomAssignments = stayRoomAssignments;
        this.charges = charges;
        this.audits = audits;
        this.reservationMapper = reservationMapper;
        this.reservationNumberGenerator = reservationNumberGenerator;
        this.stayBalanceService = stayBalanceService;
        this.clock = clock;
    }

    /**
     * Tạo một reservation nháp hoàn chỉnh từ request.
     *
     * @param request dữ liệu đặt phòng
     * @return reservation vừa tạo
     */
    @Transactional
    public Response create(CreateRequest request) {
        ReservationDraftData draftData = validateDraftData(request);
        CurrentUser user = currentUser();
        Reservation reservation =
                new Reservation(
                        UUID.randomUUID(),
                        reservationNumberGenerator.generate(),
                        draftData.guest(),
                        request.checkInDate(),
                        request.checkOutDate(),
                        request.adultCount(),
                        request.childCount(),
                        request.source(),
                        request.otaBookingReference(),
                        draftData.currency().getCurrencyCode(),
                        request.notes());
        reservation.audit(user.id());
        createRoomSnapshots(reservation, request, draftData, user).forEach(reservation::addRoom);
        reservation.replaceAccompanyingGuests(draftData.accompanyingGuests(), user.id());
        reservation.calculateTotal();
        reservations.save(reservation);
        audit(user, "CREATE", reservation, null, "DRAFT");
        return response(reservation);
    }

    /**
     * Replaces all editable data and room snapshots for a draft Reservation.
     *
     * @param id Reservation identifier
     * @param request replacement draft data
     * @return updated Reservation response
     */
    @Transactional
    public Response updateDraft(UUID id, CreateRequest request) {
        Reservation reservation = load(id);
        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw conflict("Only draft reservations can be edited");
        }
        ReservationDraftData draftData = validateDraftData(request);
        CurrentUser user = currentUser();
        List<ReservationRoom> updatedRooms = createRoomSnapshots(reservation, request, draftData, user);
        try {
            reservation.updateDraft(
                    draftData.guest(),
                    request.checkInDate(),
                    request.checkOutDate(),
                    request.adultCount(),
                    request.childCount(),
                    request.source(),
                    request.otaBookingReference(),
                    draftData.currency().getCurrencyCode(),
                    request.notes(),
                    updatedRooms,
                    draftData.accompanyingGuests(),
                    user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        reservation.audit(user.id());
        audit(user, "UPDATE", reservation, "DRAFT", "DRAFT");
        return response(reservation);
    }

    /** Validates and resolves the request data shared by create and draft editing. */
    private ReservationDraftData validateDraftData(CreateRequest request) {
        if (!request.checkOutDate().isAfter(request.checkInDate())) {
            throw bad("check_out_date must be after check_in_date");
        }
        if (request.adultCount() == null || request.adultCount() < 1) {
            throw bad("adult_count must be at least 1");
        }
        if (request.childCount() == null || request.childCount() < 0) {
            throw bad("child_count must not be negative");
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
        Map<UUID, Room> roomsById = rooms.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, room -> room));
        if (roomsById.size() != roomIds.size()) {
            throw notFound("Room");
        }
        return new ReservationDraftData(guest, currency, roomsById, resolveAccompanyingGuests(guest, request));
    }

    /**
     * Resolves the requested Accompanying Guests: every identifier must exist, none may repeat, and the Primary Guest
     * may not be among them. Guest profiles are only associated, never created here; the number of accompanying
     * Guests is independent of the adult and child counts.
     */
    private List<Guest> resolveAccompanyingGuests(Guest primary, CreateRequest request) {
        List<UUID> ids = request.accompanyingGuestIdsOrEmpty();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID accompanyingId : ids) {
            if (accompanyingId == null) {
                throw bad("An accompanying guest identifier is required");
            }
            if (!unique.add(accompanyingId)) {
                throw bad("A guest may be selected as an accompanying guest only once");
            }
            if (accompanyingId.equals(primary.getId())) {
                throw bad("The primary guest cannot also be an accompanying guest");
            }
        }
        Map<UUID, Guest> found = guests.findAllById(unique).stream()
                .collect(Collectors.toMap(Guest::getId, guest -> guest));
        if (found.size() != unique.size()) {
            throw notFound("Guest");
        }
        return ids.stream().map(found::get).toList();
    }

    /** Builds the complete replacement set of immutable room-price snapshots. */
    private List<ReservationRoom> createRoomSnapshots(
            Reservation reservation,
            CreateRequest request,
            ReservationDraftData draftData,
            CurrentUser user) {
        return request.rooms().stream()
                .map(roomRequest -> {
                    ReservationRoom reservationRoom = new ReservationRoom(
                            reservation,
                            draftData.roomsById().get(roomRequest.roomId()),
                            request.checkInDate(),
                            request.checkOutDate(),
                            scale(roomRequest.nightlyRate(), draftData.currency()));
                    reservationRoom.audit(user.id());
                    return reservationRoom;
                })
                .toList();
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
        // Capacity is checked on the locked, final room set (same rule as readiness, check-in and reassignment).
        requireAdultCapacity(reservation, lockedRooms);
        reservation.confirm();
        reservation.audit(user.id());
        audit(user, "CONFIRM", reservation, "DRAFT", "CONFIRMED");
        return response(reservation);
    }

    /**
     * Updates ONLY the guest composition (adults, children, Accompanying Guests) of a CONFIRMED Reservation that has no
     * Stay. This is a dedicated operation, not draft editing: the Primary Guest, dates, rooms, rates, source, currency
     * and notes never change, and after check-in the composition stays frozen.
     *
     * <p>Concurrency: the Reservation row is locked ({@code PESSIMISTIC_WRITE}) FIRST, and the state, the Stay check
     * and the assigned room set are read AFTER the lock. Room Reassignment changes a Reservation's rooms only while
     * holding the same row lock (it takes Rooms then the Reservation), and check-in updates the Reservation row, so
     * a concurrent reassignment or check-in is serialized with this update and capacity is judged on the
     * authoritative, current room set. Locking the Reservation only (no Room locks) keeps the Rooms-then-Reservation
     * order of the other operations deadlock-free.</p>
     *
     * <p>Everything is validated (state, counts, guests, adult capacity via {@link AdultCapacityRules}) before any
     * mutation, so a rejected update changes nothing and writes no audit entry.</p>
     *
     * @param id Reservation identifier
     * @param request new adults, children and complete Accompanying Guest set
     * @return the Reservation after the update
     * @throws GuestCompositionUpdateException if the update is not allowed or invalid
     */
    @Transactional
    public Response updateConfirmedGuestComposition(UUID id, GuestCompositionUpdateRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = reservations.findByIdForUpdate(id).orElseThrow(() -> notFound("Reservation"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.RESERVATION_NOT_CONFIRMED,
                    "Only a confirmed reservation can update its guest composition");
        }
        if (stays.existsByReservationId(id)) {
            throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.STAY_ALREADY_EXISTS,
                    "A stay already exists, so the guest composition is frozen");
        }
        if (request.adultCount() == null || request.adultCount() < 1) {
            throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.INVALID_ADULT_COUNT,
                    "adult_count must be at least 1");
        }
        if (request.childCount() == null || request.childCount() < 0) {
            throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.INVALID_CHILD_COUNT,
                    "child_count must not be negative");
        }
        List<Guest> accompanying = resolveAccompanyingGuestsForUpdate(reservation.getGuest(), request);
        List<Room> assignedRooms = reservations.findBookedRoomsByReservationIdIn(List.of(id)).stream()
                .map(row -> (Room) row[1])
                .toList();
        AdultCapacityRules.Result capacity = AdultCapacityRules.evaluate(request.adultCount(), assignedRooms);
        switch (capacity.outcome()) {
            case INSUFFICIENT_ADULT_CAPACITY -> throw new GuestCompositionUpdateException(
                    GuestCompositionUpdateException.Reason.INSUFFICIENT_ADULT_CAPACITY,
                    "Reservation has " + capacity.adultCount() + " adults but the assigned rooms support only "
                            + capacity.totalAdultCapacity() + " adults",
                    capacity.adultCount(), capacity.totalAdultCapacity());
            case CAPACITY_NOT_CONFIGURED -> throw new GuestCompositionUpdateException(
                    GuestCompositionUpdateException.Reason.CAPACITY_NOT_CONFIGURED,
                    "Room capacity is not configured for room type " + String.join(", ", capacity.unconfiguredRoomTypes()),
                    String.join(", ", capacity.unconfiguredRoomTypes()));
            case VALID -> { }
        }
        String before = describeGuestComposition(reservation);
        reservation.updateConfirmedGuestComposition(
                request.adultCount(), request.childCount(), accompanying, user.id());
        reservation.audit(user.id());
        audit(user, "UPDATE_GUEST_COMPOSITION", reservation, before, describeGuestComposition(reservation));
        return response(reservation);
    }

    private String describeGuestComposition(Reservation reservation) {
        return "adults=" + reservation.getAdultCount() + ", children=" + reservation.getChildCount()
                + ", accompanying=" + reservation.getAccompanyingGuests().size();
    }

    /** Resolves the requested Accompanying Guests for the update, with structured, localizable rejection reasons. */
    private List<Guest> resolveAccompanyingGuestsForUpdate(Guest primary, GuestCompositionUpdateRequest request) {
        List<UUID> ids = request.accompanyingGuestIdsOrEmpty();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID accompanyingId : ids) {
            if (accompanyingId == null) {
                throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.GUEST_NOT_FOUND,
                        "An accompanying guest identifier is required");
            }
            if (!unique.add(accompanyingId)) {
                throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.DUPLICATE_GUEST,
                        "A guest may be selected as an accompanying guest only once");
            }
            if (accompanyingId.equals(primary.getId())) {
                throw new GuestCompositionUpdateException(
                        GuestCompositionUpdateException.Reason.PRIMARY_GUEST_AS_ACCOMPANYING,
                        "The primary guest cannot also be an accompanying guest");
            }
        }
        Map<UUID, Guest> found = guests.findAllById(unique).stream()
                .collect(Collectors.toMap(Guest::getId, guest -> guest));
        if (found.size() != unique.size()) {
            throw new GuestCompositionUpdateException(GuestCompositionUpdateException.Reason.GUEST_NOT_FOUND,
                    "Guest not found");
        }
        return ids.stream().map(found::get).toList();
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
        // The same rule set backs the derived Arrival Readiness, so readiness and check-in cannot disagree.
        List<ArrivalIssueCode> reservationBlockers = ArrivalReadinessRules.reservationBlockers(
                reservation.getStatus(),
                reservation.getCheckInDate(),
                LocalDate.now(clock),
                () -> stays.existsByReservationId(id));
        if (!reservationBlockers.isEmpty()) {
            throw checkInConflict(reservationBlockers.get(0));
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
            if (ArrivalReadinessRules.roomBlocker(room).isPresent()) {
                throw conflict("Room is not available for check-in");
            }
        }
        // Defense in depth: capacity is re-evaluated now (not trusted from confirmation) and before any mutation.
        requireAdultCapacity(reservation, lockedRooms);
        for (Room room : lockedRooms) {
            room.occupy();
            room.audit(user.id());
        }
        reservation.checkIn();
        reservation.audit(user.id());
        Stay stay = new Stay(reservation, Instant.now(clock));
        stay.audit(user.id());
        stays.save(stay);
        seedRoomAssignments(stay, reservation, user);
        createRoomCharges(stay, reservation, user);
        audit(user, "CHECK_IN", reservation, "CONFIRMED", "CHECKED_IN");
        return response(reservation);
    }

    /**
     * Rejects the operation unless the shared {@link AdultCapacityRules} accepts the adults against the given rooms.
     * Nothing has been mutated when this is called.
     */
    private void requireAdultCapacity(Reservation reservation, List<Room> assignedRooms) {
        AdultCapacityRules.Result capacity = AdultCapacityRules.evaluate(reservation.getAdultCount(), assignedRooms);
        switch (capacity.outcome()) {
            case INSUFFICIENT_ADULT_CAPACITY -> throw conflict("Reservation has " + capacity.adultCount()
                    + " adults but the assigned rooms support only " + capacity.totalAdultCapacity() + " adults");
            case CAPACITY_NOT_CONFIGURED -> throw conflict("Room capacity is not configured for room type "
                    + String.join(", ", capacity.unconfiguredRoomTypes()));
            case VALID -> { }
        }
    }

    /**
     * Maps a Reservation-level Arrival Readiness blocker to the established check-in conflict response.
     *
     * @param blocker the first failing blocker
     * @return the conflict exception carrying the existing message
     */
    private ResponseStatusException checkInConflict(ArrivalIssueCode blocker) {
        return switch (blocker) {
            case ARRIVAL_TOO_EARLY -> conflict("Early check-in is not allowed. Create a separate DIRECT reservation "
                    + "for the additional earlier stay.");
            case STAY_ALREADY_EXISTS -> conflict("Stay already exists");
            default -> conflict("Invalid reservation state transition");
        };
    }

    /**
     * Seeds one OPEN StayRoomAssignment per booked ReservationRoom, recording the guest's actual
     * physical room occupancy from the moment of check-in. Each assignment references its exact
     * originating ReservationRoom as its lineage anchor, so any later Room Change can determine
     * the remaining planned occupancy boundary without touching this immutable booking snapshot.
     *
     * @param stay newly created checked-in Stay
     * @param reservation reservation whose booked rooms are seeded
     * @param user user attributed as the assignment creator
     */
    private void seedRoomAssignments(Stay stay, Reservation reservation, CurrentUser user) {
        for (ReservationRoom reservationRoom : reservation.getRooms()) {
            StayRoomAssignment assignment = new StayRoomAssignment(
                    stay, reservationRoom.getRoom(), reservationRoom, stay.getActualCheckInAt(), null, null);
            assignment.audit(user.id());
            stayRoomAssignments.save(assignment);
        }
    }

    /**
     * Creates the automatic ROOM Charges owed for a newly checked-in Stay, one per booked
     * ReservationRoom, copying each Charge amount directly from its immutable price snapshot.
     *
     * @param stay newly created checked-in Stay
     * @param reservation reservation whose booked rooms are charged
     * @param user user attributed as the Charge creator
     */
    private void createRoomCharges(Stay stay, Reservation reservation, CurrentUser user) {
        for (ReservationRoom reservationRoom : reservation.getRooms()) {
            long nights = ChronoUnit.DAYS.between(
                    reservationRoom.getCheckInDate(), reservationRoom.getCheckOutDate());
            Charge charge = Charge.create(
                    stay,
                    ChargeType.ROOM,
                    "Room " + reservationRoom.getRoom().getRoomNumber(),
                    BigDecimal.valueOf(nights),
                    reservationRoom.getNightlyRate(),
                    reservationRoom.getTotalAmount());
            charge.audit(user.id());
            charges.save(charge);
        }
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
        List<StayRoomAssignment> openAssignments = stayRoomAssignments.findOpenByStayId(stay.getId());
        List<UUID> roomIds = openAssignments.stream()
                .map(assignment -> assignment.getRoom().getId())
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
        Instant actualCheckOutAt = Instant.now(clock);
        try {
            for (Room room : lockedRooms) {
                room.markDirty();
                room.audit(user.id());
            }
            reservation.checkOut();
            reservation.audit(user.id());
            stay.checkOut(actualCheckOutAt);
            stay.audit(user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        for (StayRoomAssignment assignment : openAssignments) {
            assignment.close(actualCheckOutAt);
            assignment.audit(user.id());
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

    /** Resolved request data shared by Reservation create and draft-update operations. */
    private record ReservationDraftData(
            Guest guest, Currency currency, Map<UUID, Room> roomsById, List<Guest> accompanyingGuests) {}
}
