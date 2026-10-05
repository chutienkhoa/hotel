package com.example.hotel.service.booking;

import com.example.hotel.common.SupportedCurrency;
import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.BookingSource;
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
import com.example.hotel.exception.ConfirmedReservationModificationException;
import com.example.hotel.exception.ConfirmedReservationModificationException.Reason;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.exception.ReservationFieldUpdateException;
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
import com.example.hotel.service.room.RoomAvailabilityService;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** Audit action for the controlled confirmed-reservation date change. */
    public static final String CHANGE_DATES_AUDIT_ACTION = "CHANGE_RESERVATION_DATES";

    /** Audit action for the controlled confirmed-reservation OTA reference correction. */
    public static final String CORRECT_OTA_REFERENCE_AUDIT_ACTION = "CORRECT_OTA_REFERENCE";

    /** Audit action for the controlled Booking Contact update. */
    public static final String UPDATE_BOOKING_CONTACT_AUDIT_ACTION = "UPDATE_BOOKING_CONTACT";

    /** Audit action for the controlled Reservation Notes update. */
    public static final String UPDATE_NOTES_AUDIT_ACTION = "UPDATE_RESERVATION_NOTES";

    /** Sentinel meaning "exclude no Reservation" (a real Reservation identifier is never all zeros). */
    private static final UUID NO_RESERVATION = new UUID(0L, 0L);

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
    private final RoomAvailabilityService roomAvailability;
    private final PrepaymentService prepayments;
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
     * @param roomAvailability shared lifecycle-aware booking-availability primitive used by Confirm
     * @param prepayments prepayment service (cancel/no-show guard and check-in application)
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
            RoomAvailabilityService roomAvailability,
            PrepaymentService prepayments,
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
        this.roomAvailability = roomAvailability;
        this.prepayments = prepayments;
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
        ReservationDraftData draftData = validateDraftData(request, NO_RESERVATION);
        requireBookableRooms(request, draftData);
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
                        draftData.currency().name(),
                        request.notes());
        reservation.audit(user.id());
        createRoomSnapshots(reservation, request, draftData, user).forEach(reservation::addRoom);
        reservation.replaceAccompanyingGuests(draftData.accompanyingGuests(), user.id());
        reservation.calculateTotal();
        applyBookingContactDefaults(reservation, request, draftData.guest());
        reservations.save(reservation);
        audit(user, "CREATE", reservation, null, "DRAFT");
        return response(reservation);
    }

    /**
     * Defensive integrity check for a NEW Reservation: every requested Room must be bookable inventory (active and
     * neither MAINTENANCE nor OUT_OF_ORDER, see {@link Room#isBookableInventory()}). A room picker already offers only
     * such Rooms, but that filtering is not authoritative, so a crafted request must not be able to attach an
     * inactive or out-of-service Room to a DRAFT. This is deliberately NOT an availability or capacity check: booking
     * overlap and adult capacity stay Confirm-time rules, evaluated under the Room locks. Draft editing does not apply
     * this check, because the edit form retains the Rooms already assigned to the draft.
     *
     * @param request submitted creation data
     * @param draftData resolved draft data holding the requested Rooms
     */
    private void requireBookableRooms(CreateRequest request, ReservationDraftData draftData) {
        for (var roomRequest : request.rooms()) {
            Room room = draftData.roomsById().get(roomRequest.roomId());
            if (!room.isBookableInventory()) {
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.create.error.roomNotBookable",
                        "Room is not bookable inventory",
                        room.getRoomNumber(),
                        room.getId());
            }
        }
    }

    /**
     * Sets the new Reservation's Booking Contact snapshot as ONE independent whole, never mixing fields from two
     * different people: when every submitted field is blank/omitted, the ENTIRE snapshot is copied from the
     * Primary Guest at this moment (a one-time COPY, never a live reference); when at least one field is
     * explicitly supplied, the submitted values are kept exactly as given — including any blank sibling fields,
     * which stay {@code null} rather than being individually backfilled from the Primary Guest. This runs only at
     * creation; draft editing and the controlled Booking Contact update never re-apply this default, so a later
     * Primary Guest change never silently overwrites an already-set Booking Contact.
     *
     * @param reservation newly constructed Reservation (still DRAFT, not yet persisted)
     * @param request submitted creation data
     * @param guest the resolved Primary Guest, used only as the whole-snapshot default source
     */
    private void applyBookingContactDefaults(Reservation reservation, CreateRequest request, Guest guest) {
        String name = normalizeContactValue(request.bookingContactName());
        String phone = normalizeContactValue(request.bookingContactPhone());
        String email = normalizeContactValue(request.bookingContactEmail());
        if (name == null && phone == null && email == null) {
            reservation.changeBookingContact(
                    EffectiveBookingContact.guestFullName(guest), guest.getPhone(), guest.getEmail());
        } else {
            reservation.changeBookingContact(name, phone, email);
        }
    }

    /** Normalizes a Booking Contact or Notes value: blank and {@code null} both become {@code null}. */
    private static String normalizeContactValue(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Enforces the approved OTA external booking identity: for a non-DIRECT Reservation the pair
     * {@code (source, otaBookingReference)} is permanently unique. The reference is compared exactly as stored,
     * because V1 keeps a staff-entered reference verbatim; no case folding, trimming or punctuation normalization is
     * applied here, since inventing one would silently change which existing bookings are considered the same.
     *
     * <p>A terminal state does NOT release the identity: a CANCELLED, NO_SHOW or CHECKED_OUT Reservation still holds
     * its reference, because a genuinely new OTA booking always carries a new one. DIRECT Reservations never
     * participate, since the domain nulls their reference on construction.</p>
     *
     * <p>This check exists to produce a useful, localized message. It is NOT the concurrency guarantee: the partial
     * unique index {@code ux_reservation_ota_identity} (migration V42) is the authoritative barrier, so two
     * concurrent creations of the same identity can never both commit even though both may pass this check.</p>
     *
     * @param source submitted booking source
     * @param otaBookingReference submitted external booking reference
     * @param excludedReservationId the Reservation whose own identity must not count as a duplicate of itself, or
     *     {@link #NO_RESERVATION} when creating
     */
    private void requireAvailableOtaIdentity(
            BookingSource source, String otaBookingReference, UUID excludedReservationId) {
        if (source == null || source == BookingSource.DIRECT) {
            return;
        }
        if (otaBookingReference == null || otaBookingReference.isBlank()) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.ota.error.referenceRequired",
                    "OTA Booking Reference is required for this source");
        }
        if (reservations.existsByOtaIdentity(source, otaBookingReference, excludedReservationId)) {
            throw new LocalizedResponseStatusException(HttpStatus.CONFLICT,
                    "reservation.ota.error.duplicateIdentity",
                    "This OTA booking reference is already used by another reservation for this source",
                    source.name(), otaBookingReference);
        }
    }

    /**
     * Read-only pre-check of the OTA external identity for a not-yet-created Reservation, so a pre-persistence
     * review can report a duplicate or missing reference early. It applies exactly the rule {@link #create} applies;
     * the creation itself (and the unique index) remain the authoritative barrier.
     *
     * @param source submitted booking source
     * @param otaBookingReference submitted external booking reference
     * @throws org.springframework.web.server.ResponseStatusException if the reference is missing or already used
     */
    @Transactional
    public void requireOtaIdentityAvailableForNewReservation(BookingSource source, String otaBookingReference) {
        requireAvailableOtaIdentity(source, otaBookingReference, NO_RESERVATION);
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
        ReservationDraftData draftData = validateDraftData(request, id);
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
                    draftData.currency().name(),
                    request.notes(),
                    updatedRooms,
                    draftData.accompanyingGuests(),
                    user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        // Draft editing never re-applies the create-time Primary Guest default: whatever the form submits (including
        // blank, which clears it) is stored exactly as submitted, so an edited Primary Guest never silently
        // overwrites an already-set Booking Contact.
        reservation.changeBookingContact(
                normalizeContactValue(request.bookingContactName()),
                normalizeContactValue(request.bookingContactPhone()),
                normalizeContactValue(request.bookingContactEmail()));
        reservation.audit(user.id());
        audit(user, "UPDATE", reservation, "DRAFT", "DRAFT");
        return response(reservation);
    }

    /**
     * Validates and resolves the request data shared by create and draft editing.
     *
     * @param request submitted draft data
     * @param excludedReservationId the Reservation being edited, whose own OTA external identity must not count as a
     *     duplicate of itself, or {@link #NO_RESERVATION} when creating
     * @return the resolved draft data
     */
    private ReservationDraftData validateDraftData(CreateRequest request, UUID excludedReservationId) {
        if (!request.checkOutDate().isAfter(request.checkInDate())) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.create.error.checkOutAfterCheckIn",
                    "check_out_date must be after check_in_date");
        }
        requireAvailableOtaIdentity(request.source(), request.otaBookingReference(), excludedReservationId);
        if (request.adultCount() == null || request.adultCount() < 1) {
            throw bad("adult_count must be at least 1");
        }
        if (request.childCount() == null || request.childCount() < 0) {
            throw bad("child_count must not be negative");
        }
        SupportedCurrency currency = SupportedCurrency.find(request.currency())
                .orElseThrow(() -> bad("Unsupported currency"));
        // V1 Reservation/Folio currency is VND only (USD stays a Payment tender currency). Enforced here so every
        // creation path (MVC, REST, Walk-in, OTA entry, draft edit) is covered, not just the form and DTO validation.
        if (currency != SupportedCurrency.VND) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.currency.error.vndOnly",
                    "Reservation currency must be VND");
        }
        Guest guest = guests.findById(request.guestId())
                .orElseThrow(() -> new LocalizedResponseStatusException(HttpStatus.NOT_FOUND,
                        "reservation.create.error.guestNotFound", "Guest not found"));
        Set<UUID> roomIds = new HashSet<>();
        for (var roomRequest : request.rooms()) {
            if (!roomIds.add(roomRequest.roomId())) {
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.create.error.duplicateRoom",
                        "A room may be assigned once per reservation");
            }
        }
        Map<UUID, Room> roomsById = rooms.findAllById(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, room -> room));
        if (roomsById.size() != roomIds.size()) {
            throw new LocalizedResponseStatusException(HttpStatus.NOT_FOUND,
                    "reservation.create.error.roomNotFound", "Room not found");
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
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.create.error.accompanyingRequired",
                        "An accompanying guest identifier is required");
            }
            if (!unique.add(accompanyingId)) {
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.create.error.accompanyingDuplicate",
                        "A guest may be selected as an accompanying guest only once");
            }
            if (accompanyingId.equals(primary.getId())) {
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.create.error.accompanyingIsPrimary",
                        "The primary guest cannot also be an accompanying guest");
            }
        }
        Map<UUID, Guest> found = guests.findAllById(unique).stream()
                .collect(Collectors.toMap(Guest::getId, guest -> guest));
        if (found.size() != unique.size()) {
            throw new LocalizedResponseStatusException(HttpStatus.NOT_FOUND,
                    "reservation.create.error.accompanyingNotFound", "Guest not found");
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
                            scale(roomRequest.nightlyRate(), draftData.currency(),
                                    draftData.roomsById().get(roomRequest.roomId())));
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
        CurrentUser user = currentUser();
        // Lifecycle lock order: Rooms (sorted) first, then the Reservation row, then state is read fresh.
        LockedReservation locked = lockRoomsThenReservation(id);
        Reservation reservation = locked.reservation();
        List<Room> lockedRooms = locked.rooms();
        List<UUID> roomIds = lockedRooms.stream().map(Room::getId).sorted().toList();
        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw conflict("Invalid reservation state transition");
        }
        // Lifecycle-aware inventory check under the room locks taken above (CONFIRMED via ReservationRoom, CHECKED_IN
        // via the actual StayRoomAssignments), in one bounded query for all of the reservation's rooms.
        if (!roomAvailability
                .conflictedRoomIds(roomIds, reservation.getCheckInDate(), reservation.getCheckOutDate())
                .isEmpty()) {
            throw conflict("Room is already booked for these dates");
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

    /**
     * Changes the dates of a CONFIRMED Reservation before Stay creation. Rooms are locked in stable identifier order
     * before the Reservation row, matching check-in and pre-check-in reassignment. Every mutable condition is then
     * read again under those locks. The current Reservation's own confirmed rows are explicitly excluded from the
     * shared inventory query; other Reservations and active Stay assignments are not excluded. Rates and Room
     * references are preserved, room totals are recalculated for the new night count, and the Reservation total is
     * recalculated from those room totals. Active PAID prepayments may not exceed that proposed total.
     *
     * @param id Reservation identifier
     * @param request replacement check-in and check-out dates
     * @return the updated Reservation response
     * @throws ConfirmedReservationModificationException if lifecycle, dates, inventory, concurrency or prepayment
     *     invariants reject the operation
     */
    @Transactional
    public Response changeConfirmedDates(UUID id, ReservationDateChangeRequest request) {
        CurrentUser user = currentUser();
        LockedReservation locked = lockRoomsThenReservation(id, true);
        Reservation reservation = locked.reservation();
        requireConfirmedWithoutStay(reservation);
        LocalDate newCheckInDate = request == null ? null : request.newCheckInDate();
        LocalDate newCheckOutDate = request == null ? null : request.newCheckOutDate();
        requireValidChangedDates(newCheckInDate, newCheckOutDate);

        List<UUID> roomIds = locked.rooms().stream().map(Room::getId).sorted().toList();
        if (!roomAvailability.conflictedRoomIdsExcludingReservation(
                        roomIds, newCheckInDate, newCheckOutDate, reservation.getId())
                .isEmpty()) {
            throw modification(Reason.ROOM_UNAVAILABLE, "Room is already booked for these dates");
        }

        long nights = ChronoUnit.DAYS.between(newCheckInDate, newCheckOutDate);
        BigDecimal proposedTotal = reservation.getRooms().stream()
                .map(room -> room.getNightlyRate().multiply(BigDecimal.valueOf(nights)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal activePrepayments = prepayments.activePaidTotal(id);
        if (activePrepayments.compareTo(proposedTotal) > 0) {
            throw modification(
                    Reason.PREPAYMENT_EXCEEDS_TOTAL,
                    "Active prepayments exceed the proposed reservation total; refund the excess before shortening",
                    activePrepayments,
                    proposedTotal,
                    reservation.getCurrency());
        }

        String oldValue = describeDateChange(
                reservation.getCheckInDate(), reservation.getCheckOutDate(), reservation.getTotalAmount());
        reservation.changeConfirmedDates(newCheckInDate, newCheckOutDate);
        for (ReservationRoom reservationRoom : reservation.getRooms()) {
            reservationRoom.audit(user.id());
        }
        reservation.audit(user.id());
        audit(user, CHANGE_DATES_AUDIT_ACTION, reservation, oldValue,
                describeDateChange(newCheckInDate, newCheckOutDate, reservation.getTotalAmount()));
        return response(reservation);
    }

    /**
     * Corrects only the OTA booking reference of a CONFIRMED non-DIRECT Reservation before Stay creation. Source and
     * legacy {@code externalBookingId} are never changed; the submitted value follows the existing reference
     * behavior (blank detection and 255-character maximum, with non-blank text otherwise stored verbatim).
     *
     * @param id Reservation identifier
     * @param request corrected OTA reference
     * @return the updated Reservation response
     * @throws ConfirmedReservationModificationException if lifecycle, source or reference validation rejects it
     */
    @Transactional
    public Response correctOtaBookingReference(UUID id, OtaReferenceCorrectionRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = loadForUpdate(id);
        requireConfirmedWithoutStay(reservation);
        if (reservation.getSource() == BookingSource.DIRECT) {
            throw modification(Reason.DIRECT_RESERVATION, "DIRECT reservations cannot correct an OTA reference");
        }
        String correctedReference = request == null ? null : request.otaBookingReference();
        if (correctedReference == null || correctedReference.isBlank()) {
            throw modification(Reason.OTA_REFERENCE_REQUIRED, "OTA Booking Reference is required for this source");
        }
        if (correctedReference.length() > 255) {
            throw modification(Reason.OTA_REFERENCE_TOO_LONG,
                    "OTA Booking Reference must not exceed 255 characters", 255);
        }
        // Correcting a reference must not let this Reservation take over an identity another Reservation already
        // holds; the Reservation's own current identity is excluded so re-submitting the same value is a no-op.
        requireAvailableOtaIdentity(reservation.getSource(), correctedReference, id);

        String previousReference = reservation.getOtaBookingReference();
        reservation.correctOtaBookingReference(correctedReference);
        reservation.audit(user.id());
        audit(user, CORRECT_OTA_REFERENCE_AUDIT_ACTION, reservation,
                "otaBookingReference=" + String.valueOf(previousReference),
                "otaBookingReference=" + correctedReference);
        return response(reservation);
    }

    /**
     * Replaces the Booking Contact snapshot (name, phone, email) of a Reservation that has not yet been checked
     * out, cancelled or marked no-show. Unlike Change Dates and OTA correction, this operation remains available
     * through DRAFT, CONFIRMED and CHECKED_IN, and does not require the absence of a Stay: Booking Contact is
     * independent from Room/rate/Stay state. Only the three Booking Contact fields change; nothing else is read
     * from or written to the Primary Guest, Accompanying Guests, OTA reference or {@code externalBookingId}. The
     * audit entry never records the submitted name, phone or email — only which fields actually changed.
     *
     * @param id Reservation identifier
     * @param request replacement Booking Contact values (every field individually optional)
     * @return the updated Reservation response
     * @throws ReservationFieldUpdateException if the Reservation's lifecycle no longer allows this change
     */
    @Transactional
    public Response updateBookingContact(UUID id, BookingContactUpdateRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = loadForUpdate(id);
        requireEditableForContactAndNotes(reservation);
        String newName = normalizeContactValue(request == null ? null : request.bookingContactName());
        String newPhone = normalizeContactValue(request == null ? null : request.bookingContactPhone());
        String newEmail = normalizeContactValue(request == null ? null : request.bookingContactEmail());
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(reservation.getBookingContactName(), newName)) {
            changedFields.add("name");
        }
        if (!Objects.equals(reservation.getBookingContactPhone(), newPhone)) {
            changedFields.add("phone");
        }
        if (!Objects.equals(reservation.getBookingContactEmail(), newEmail)) {
            changedFields.add("email");
        }
        reservation.changeBookingContact(newName, newPhone, newEmail);
        reservation.audit(user.id());
        audit(user, UPDATE_BOOKING_CONTACT_AUDIT_ACTION, reservation, null,
                "changedFields=" + String.join(",", changedFields));
        return response(reservation);
    }

    /**
     * Replaces the internal operational Reservation Notes of a Reservation that has not yet been checked out,
     * cancelled or marked no-show. This is the same lifecycle boundary as Booking Contact and, like it, remains
     * available through CHECKED_IN. Only {@link Reservation#getNotes()} changes. The audit entry never records the
     * submitted notes content; action identity (who, when, which Reservation) is sufficient.
     *
     * @param id Reservation identifier
     * @param request replacement notes (optional; blank/{@code null} clears the notes)
     * @return the updated Reservation response
     * @throws ReservationFieldUpdateException if the Reservation's lifecycle no longer allows this change
     */
    @Transactional
    public Response updateReservationNotes(UUID id, NotesUpdateRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = loadForUpdate(id);
        requireEditableForContactAndNotes(reservation);
        reservation.changeNotes(request == null ? null : request.notes());
        reservation.audit(user.id());
        audit(user, UPDATE_NOTES_AUDIT_ACTION, reservation, null, null);
        return response(reservation);
    }

    /** Rejects a Booking Contact or Notes change once the Reservation is CHECKED_OUT, CANCELLED or NO_SHOW. */
    private void requireEditableForContactAndNotes(Reservation reservation) {
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.CHECKED_OUT || status == ReservationStatus.CANCELLED
                || status == ReservationStatus.NO_SHOW) {
            throw new ReservationFieldUpdateException(ReservationFieldUpdateException.Reason.RESERVATION_LOCKED,
                    "This reservation's lifecycle no longer allows this change");
        }
    }

    /** Rejects a controlled pre-check-in modification outside the shared lifecycle boundary. */
    private void requireConfirmedWithoutStay(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw modification(Reason.RESERVATION_NOT_CONFIRMED,
                    "Only a confirmed reservation can use this operation");
        }
        if (stays.existsByReservationId(reservation.getId())) {
            throw modification(Reason.STAY_ALREADY_EXISTS,
                    "A Stay already exists, so this reservation can no longer be modified");
        }
    }

    /** Validates the replacement interval against the deterministic hotel business date. */
    private void requireValidChangedDates(LocalDate checkInDate, LocalDate checkOutDate) {
        LocalDate hotelToday = LocalDate.now(clock);
        if (checkInDate == null || checkInDate.isBefore(hotelToday)) {
            throw modification(Reason.CHECK_IN_BEFORE_TODAY,
                    "New check-in date must be today or later", hotelToday);
        }
        if (checkOutDate == null || !checkOutDate.isAfter(checkInDate)) {
            throw modification(Reason.CHECK_OUT_NOT_AFTER_CHECK_IN,
                    "New check-out date must be after the new check-in date");
        }
    }

    /** Builds the stable audit representation of one date-and-total snapshot. */
    private String describeDateChange(LocalDate checkInDate, LocalDate checkOutDate, BigDecimal total) {
        return "checkInDate=" + checkInDate + ", checkOutDate=" + checkOutDate + ", totalAmount="
                + total.toPlainString();
    }

    /** Creates a structured controlled-modification rejection for REST and localized MVC handling. */
    private ConfirmedReservationModificationException modification(
            Reason reason, String detail, Object... arguments) {
        return new ConfirmedReservationModificationException(reason, detail, arguments);
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
     * Hủy reservation nháp (DRAFT, an abandoned/invalid draft) hoặc đã xác nhận, yêu cầu một lý do hủy hợp lệ.
     * The reason is stored on the Reservation and is never duplicated into {@link AuditLog}; only the state
     * transition is audited. Any other source state is rejected by the entity with the existing 409.
     *
     * @param id định danh reservation
     * @param request the required cancellation reason
     * @return reservation sau khi hủy
     */
    @Transactional
    public Response cancel(UUID id, CancelReservationRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = loadForUpdate(id);
        ReservationStatus previousStatus = reservation.getStatus();
        CancellationReasonCode reasonCode = request == null ? null : request.cancellationReasonCode();
        String reasonDetail = trimToNull(request == null ? null : request.cancellationReasonDetail());
        if (previousStatus == ReservationStatus.DRAFT || previousStatus == ReservationStatus.CONFIRMED) {
            requireValidCancellationReason(reasonCode, reasonDetail);
            prepayments.requireNoActivePrepayments(id, "payment.prepayment.error.blocksCancel",
                    "This reservation has active prepayments. Refund them before cancellation.");
        }
        try {
            reservation.cancel(reasonCode, reasonDetail);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        reservation.audit(user.id());
        audit(user, "CANCEL", reservation, previousStatus.name(), reservation.getStatus().name());
        return response(reservation);
    }

    /** Re-validates the cancellation reason server-side, independent of DTO-level Bean Validation. */
    private void requireValidCancellationReason(CancellationReasonCode reasonCode, String trimmedDetail) {
        if (reasonCode == null) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.cancel.error.reasonRequired", "Cancellation reason is required");
        }
        if (reasonCode == CancellationReasonCode.OTHER && trimmedDetail == null) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.cancel.error.detailRequiredForOther",
                    "Cancellation reason detail is required when reason is OTHER");
        }
    }

    /**
     * Đánh dấu reservation đã xác nhận là no-show, yêu cầu một lý do bắt buộc và chỉ khi ngày nhận phòng đã
     * qua (theo hotel Clock): a same-day or future arrival cannot be marked NO_SHOW. The reason is stored on
     * the Reservation and is never duplicated into {@link AuditLog}; only the state transition is audited.
     *
     * @param id định danh reservation
     * @param request the required no-show reason
     * @return reservation sau khi cập nhật
     */
    @Transactional
    public Response noShow(UUID id, NoShowReservationRequest request) {
        CurrentUser user = currentUser();
        Reservation reservation = loadForUpdate(id);
        ReservationStatus previousStatus = reservation.getStatus();
        String trimmedReason = trimToNull(request == null ? null : request.noShowReason());
        if (previousStatus == ReservationStatus.CONFIRMED) {
            if (trimmedReason == null) {
                throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                        "reservation.noShow.error.reasonRequired", "No-show reason is required");
            }
            LocalDate hotelToday = LocalDate.now(clock);
            if (!ReservationActionRules.noShowDateReached(reservation.getCheckInDate(), hotelToday)) {
                throw new LocalizedResponseStatusException(HttpStatus.CONFLICT,
                        "reservation.noShow.error.notEligible",
                        "No-show is only allowed once the check-in date has passed");
            }
            prepayments.requireNoActivePrepayments(id, "payment.prepayment.error.blocksNoShow",
                    "This reservation has active prepayments. Refund them before marking no-show.");
        }
        try {
            reservation.noShow(trimmedReason);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        reservation.audit(user.id());
        audit(user, "NO_SHOW", reservation, previousStatus.name(), reservation.getStatus().name());
        return response(reservation);
    }

    /** Trims a submitted reason value; blank and {@code null} both become {@code null}. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Check-in reservation, tạo Stay và chuyển phòng sang OCCUPIED.
     *
     * @param id định danh reservation
     * @return reservation sau khi check-in
     */
    @Transactional
    public Response checkIn(UUID id) {
        CurrentUser user = currentUser();
        // Lifecycle lock order: Rooms (sorted) first, then the Reservation row. The Reservation is loaded only AFTER
        // its lock, so a concurrent cancel/no-show/prepayment/refund is serialized and no stale state is used.
        LockedReservation locked = lockRoomsThenReservation(id);
        Reservation reservation = locked.reservation();
        List<Room> lockedRooms = locked.rooms();
        // The same rule set backs the derived Arrival Readiness, so readiness and check-in cannot disagree.
        List<ArrivalIssueCode> reservationBlockers = ArrivalReadinessRules.reservationBlockers(
                reservation.getStatus(),
                reservation.getCheckInDate(),
                LocalDate.now(clock),
                () -> stays.existsByReservationId(id));
        if (!reservationBlockers.isEmpty()) {
            throw checkInConflict(reservationBlockers.get(0));
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
        Stay newStay = new Stay(reservation, Instant.now(clock));
        newStay.audit(user.id());
        // Use the managed instance returned by save: prepayments (managed Payment rows) are attached to it.
        Stay stay = stays.save(newStay);
        seedRoomAssignments(stay, reservation, user);
        createRoomCharges(stay, reservation, user);
        // The SAME prepayment rows (money received before check-in) join the Stay folio; nothing is copied.
        prepayments.applyToStay(reservation, stay, user);
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
            Charge charge = Charge.createOriginalRoomCharge(
                    stay,
                    reservationRoom,
                    "Room " + reservationRoom.getRoom().getRoomNumber(),
                    BigDecimal.valueOf(nights));
            charge.audit(user.id());
            charges.save(charge);
        }
    }

    /**
     * Checks out every room assigned to a checked-in reservation in one transaction.
     *
     * <p>Concurrency: the Stay is locked first (the post-check-in serialization boundary shared with Stay Extension,
     * Room Change, Charge and Payment), and the Reservation row is then locked and loaded with
     * {@link #loadForUpdate(UUID)} — not merely read. Check-out writes the whole Reservation row, while Booking
     * Contact and Reservation Notes stay editable through CHECKED_IN and serialize on that same row lock; loading the
     * Reservation without the lock would let check-out overwrite a concurrently committed Booking Contact or Notes
     * edit with its own older snapshot. Holding the lock makes the two orders the only possible outcomes: the edit
     * commits first and check-out sees it, or check-out commits first and the edit is rejected because the
     * Reservation is CHECKED_OUT.</p>
     *
     * @param id reservation identifier
     * @return reservation after its completed check-out
     * @throws ResponseStatusException if the reservation, Stay, balance, or Room states prevent check-out
     */
    @Transactional
    public Response checkOut(UUID id) {
        CurrentUser user = currentUser();
        // Lock order: the Stay first (shared post-check-in boundary), then the Reservation row, then the current
        // Rooms. The Reservation is loaded only AFTER its own lock, so its status, planned check-out and editable
        // Booking Contact/Notes are never older than the lock and cannot be written back stale.
        Optional<Stay> lockedStay = stays.findByReservationIdForUpdate(id);
        Reservation reservation = loadForUpdate(id);
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw conflict("Invalid reservation state transition");
        }
        Stay stay = lockedStay.orElseThrow(() -> conflict("Stay not found for reservation"));
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Invalid stay state transition");
        }
        // Overdue Departure: the additional night(s) must be resolved through Stay Extension (billed) first.
        if (OverdueDeparture.isOverdue(reservation.getCheckOutDate(), LocalDate.now(clock))) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT,
                    "reservation.checkout.error.overdue",
                    "Overdue stay must be extended before check-out",
                    OverdueDeparture.overdueDays(reservation.getCheckOutDate(), LocalDate.now(clock)));
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

    /** The rooms locked first and the Reservation loaded fresh under its row lock. */
    private record LockedReservation(Reservation reservation, List<Room> rooms) {}

    /**
     * Applies the pre-check-in lifecycle lock order shared with Room Reassignment: the Reservation's rooms are locked
     * in sorted-id order, THEN the Reservation row is locked and loaded (first load, so its state is never older than
     * the lock). The room set read before the locks is compared with the authoritative one afterwards.
     */
    private LockedReservation lockRoomsThenReservation(UUID id) {
        return lockRoomsThenReservation(id, false);
    }

    /**
     * Applies the shared pre-check-in lock order and optionally emits the structured concurrent-change rejection
     * required by the controlled modification UI.
     *
     * @param id Reservation identifier
     * @param structuredConcurrentFailure whether a changed Room set uses the modification exception
     * @return locked Reservation and Rooms
     */
    private LockedReservation lockRoomsThenReservation(UUID id, boolean structuredConcurrentFailure) {
        List<UUID> roomIds = reservations.findRoomIdsByReservationId(id).stream().sorted().toList();
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw notFound("Room");
        }
        Reservation reservation = loadForUpdate(id);
        List<UUID> currentIds = reservation.getRooms().stream()
                .map(reservationRoom -> reservationRoom.getRoom().getId())
                .sorted()
                .toList();
        if (!currentIds.equals(roomIds)) {
            if (structuredConcurrentFailure) {
                throw modification(Reason.ROOMS_CHANGED_CONCURRENTLY,
                        "Reservation rooms changed concurrently; retry");
            }
            throw conflict("Reservation rooms changed concurrently; retry");
        }
        return new LockedReservation(reservation, lockedRooms);
    }

    /** Locks and loads a Reservation row ({@code PESSIMISTIC_WRITE}); its state is read after the lock. */
    private Reservation loadForUpdate(UUID id) {
        return reservations.findByIdForUpdate(id).orElseThrow(() -> notFound("Reservation"));
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
     * @param room phòng sở hữu giá, dùng để báo lỗi gắn với đúng dòng phòng
     * @return giá đã chuẩn hóa
     * @throws ResponseStatusException nếu giá vượt độ chính xác tiền tệ
     */
    private BigDecimal scale(BigDecimal value, SupportedCurrency currency, Room room) {
        if (!currency.hasValidPrecision(value)) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reservation.create.error.nightlyRateScale",
                    "Rate exceeds currency precision",
                    room.getRoomNumber(),
                    room.getId());
        }
        return currency.normalize(value);
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
            Guest guest,
            SupportedCurrency currency,
            Map<UUID, Room> roomsById,
            List<Guest> accompanyingGuests) {}
}
