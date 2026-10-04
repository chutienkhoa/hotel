package com.example.hotel.service.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInRoomLine;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.OtaEntryReviewResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.dto.booking.response.WalkInRoomOption;
import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.exception.WalkInReviewException;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.customer.GuestDocumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the V1 Check-in operational flows (Existing Reservation review, OTA Booking Not
 * Entered, Walk-in) strictly by reusing the existing Reservation lifecycle operations. This
 * service introduces no alternate Reservation/Stay creation path; every persisted change happens
 * through {@link ReservationService#create}, {@link ReservationService#confirm}, and
 * {@link ReservationService#checkIn}.
 */
@Service
public class CheckInService {

    private final ReservationRepository reservationRepository;
    private final ReservationQueryService reservationQueryService;
    private final ReservationService reservationService;
    private final RoomRepository roomRepository;
    private final RoomAvailabilityService roomAvailability;
    private final StayRepository stayRepository;
    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;
    private final GuestDocumentService guestDocumentService;
    private final Clock clock;

    /**
     * Creates the Check-in orchestration service with its collaborators.
     *
     * @param reservationRepository repository used to load Reservations for Review
     * @param reservationQueryService service used to reuse existing Reservation search/filtering
     * @param reservationService service holding the authoritative create/confirm/checkIn lifecycle
     * @param roomRepository repository used to resolve Rooms for the Walk-in preview
     * @param roomAvailability service that lists Rooms ready for an immediate check-in
     * @param stayRepository repository used to detect an already existing Stay for Arrival Readiness
     * @param guestRepository repository used to resolve the selected Guest for Review/Walk-in
     * @param guestMapper mapper used to compute the Guest display name
     * @param guestDocumentService service used to read passport availability
     * @param clock authoritative hotel business clock
     */
    public CheckInService(
            ReservationRepository reservationRepository,
            ReservationQueryService reservationQueryService,
            ReservationService reservationService,
            RoomRepository roomRepository,
            RoomAvailabilityService roomAvailability,
            StayRepository stayRepository,
            GuestRepository guestRepository,
            GuestMapper guestMapper,
            GuestDocumentService guestDocumentService,
            Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationQueryService = reservationQueryService;
        this.reservationService = reservationService;
        this.roomRepository = roomRepository;
        this.roomAvailability = roomAvailability;
        this.stayRepository = stayRepository;
        this.guestRepository = guestRepository;
        this.guestMapper = guestMapper;
        this.guestDocumentService = guestDocumentService;
        this.clock = clock;
    }


    /**
     * Searches CONFIRMED Reservations only, reusing the existing Reservation list query
     * infrastructure. Search is local-database only; no external OTA API is contacted.
     *
     * @param criteria submitted Reservation Number / Guest / OTA Booking Reference filters
     * @param page zero-based requested page number
     * @return a page of CONFIRMED Reservations matching the supplied filters
     */
    @Transactional(readOnly = true)
    public Page<ReservationSummaryResponse> searchConfirmedReservations(ReservationSearchCriteria criteria, int page) {
        criteria.setStatus(ReservationStatus.CONFIRMED);
        if (TableSorts.CHECK_IN.key(criteria.getSort(), criteria.getDir()) == null) {
            criteria.setSort(null);
            criteria.setDir(null);
        }
        return reservationQueryService.findPage(criteria, page);
    }

    /**
     * Builds the read-only Existing Reservation Check-in Review for one Reservation.
     *
     * @param reservationId Reservation identifier
     * @return the complete Review data, including the Early/Normal/Late timing classification
     * @throws ResponseStatusException if no Reservation exists for the identifier
     */
    @Transactional(readOnly = true)
    public CheckInReviewResponse review(UUID reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        LocalDate today = LocalDate.now(clock);
        CheckInTiming timing = ArrivalReadinessRules.classify(today, reservation.getCheckInDate());
        Guest guest = reservation.getGuest();
        boolean passportAvailable = guestDocumentService.hasPassport(guest.getId());
        List<GuestDocumentResponse> passports = guestDocumentService.findPassports(guest.getId());
        UUID firstPassportDocumentId = passports.isEmpty() ? null : passports.get(0).id();
        EffectiveBookingContact contact = EffectiveBookingContact.of(reservation);
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                reservation.getStatus(),
                reservation.getCheckInDate(),
                today,
                reservation.getStatus() == ReservationStatus.CONFIRMED
                        && stayRepository.existsByReservationId(reservation.getId()),
                reservation.getAdultCount(),
                reservation.getRooms().stream().map(ReservationRoom::getRoom).toList(),
                passportAvailable);
        boolean eligible = readiness.blockers().isEmpty();
        return new CheckInReviewResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getStatus().name(),
                eligible,
                timing,
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                today,
                Instant.now(clock),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                guest.getId(),
                guestMapper.toLookupResponse(guest).fullName(),
                guest.getGuestCode(),
                guest.getNationality(),
                passportAvailable,
                reservation.getRooms().stream().map(this::toRoomLine).toList(),
                reservation.getTotalAmount(),
                reservation.getCurrency(),
                readiness,
                reservation.getAdultCount(),
                reservation.getChildCount(),
                reservationQueryService.findAccompanyingGuests(reservation.getId()),
                reservation.getReservedAt(),
                guest.getDateOfBirth(),
                guest.getPhone(),
                guest.getEmail(),
                contact.name(),
                contact.phone(),
                contact.email(),
                contact.fromPrimaryGuest(),
                firstPassportDocumentId,
                timing == CheckInTiming.LATE ? ChronoUnit.DAYS.between(reservation.getCheckInDate(), today) : 0L);
    }

    /**
     * Executes the actual Check-in for an existing Reservation. All Early/status validation is
     * enforced inside {@link ReservationService#checkIn}, so direct POST/API invocation cannot
     * bypass the Early check-in rule.
     *
     * @param reservationId Reservation identifier
     * @return the Reservation response after Check-in
     */
    @Transactional
    public Response confirmCheckIn(UUID reservationId) {
        return reservationService.checkIn(reservationId);
    }

    /**
     * Lists Rooms that can be checked in immediately for the entire requested date range: active AVAILABLE
     * Rooms with no overlapping CONFIRMED/CHECKED_IN Reservation. DIRTY, CLEANING, MAINTENANCE, OUT_OF_ORDER and
     * OCCUPIED Rooms are not offered, so the Walk-in list matches what check-in will accept. This list is UI
     * guidance only; the final Walk-in confirmation independently re-locks and re-validates.
     *
     * @param checkInDate inclusive requested check-in date
     * @param checkOutDate exclusive requested check-out date
     * @return check-in-ready Rooms for the range
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> availableRoomsForRange(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomAvailability.checkInReadyRoomsForPeriod(checkInDate, checkOutDate);
    }

    /**
     * Lists active Rooms available for a Walk-in, using the hotel current date as the Walk-in
     * check-in date and the requested date as the check-out date.
     *
     * @param checkOutDate staff-requested check-out date
     * @return active Rooms with no overlapping CONFIRMED/CHECKED_IN Reservation for the range
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> availableRoomsForWalkIn(LocalDate checkOutDate) {
        return availableRoomsForRange(LocalDate.now(clock), checkOutDate);
    }

    /**
     * Creates and confirms an OTA Reservation for a booking that already exists at the OTA but
     * was not yet entered into the Hotel System. DIRECT is not accepted by this flow.
     *
     * @param request the submitted OTA Reservation data (source must not be DIRECT)
     * @return the confirmed Reservation response, ready for the Check-in Review flow
     */
    @Transactional
    public Response createOtaEntry(CreateRequest request) {
        if (request.source() == BookingSource.DIRECT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "An OTA source is required for OTA Booking Not Entered.");
        }
        Response created = reservationService.create(request);
        return reservationService.confirm(created.id());
    }

    /**
     * Builds the read-only OTA Reservation Summary without persisting anything. It applies the rules that
     * {@link #createOtaEntry} enforces authoritatively later, so a rejected selection is reported on the form instead
     * of after the user pressed Create Reservation: OTA source and a free, non-empty external identity, a stay of at
     * least one night, distinct Rooms that are bookable and conflict-free for the whole period (the same predicate as
     * {@link #otaRoomOptions}), and adults within the Rooms' adult capacity ({@link AdultCapacityRules}).
     *
     * @param request submitted OTA Reservation data
     * @return the OTA preview, including the calculated total
     * @throws WalkInReviewException when a selection rule is not satisfied
     * @throws ResponseStatusException when the source, Guest or Room is invalid, or the OTA identity is unusable
     */
    @Transactional(readOnly = true)
    public OtaEntryReviewResponse reviewOtaEntry(CreateRequest request) {
        if (request.source() == BookingSource.DIRECT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "An OTA source is required for OTA Booking Not Entered.");
        }
        Guest guest = guestRepository
                .findById(request.guestId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found"));
        if (!request.checkOutDate().isAfter(request.checkInDate())) {
            throw new WalkInReviewException(
                    WalkInReviewException.Reason.CHECK_OUT_NOT_AFTER_CHECK_IN,
                    "Check-out date must be after the check-in date");
        }
        reservationService.requireOtaIdentityAvailableForNewReservation(request.source(), request.otaBookingReference());
        Set<UUID> bookable = roomAvailability
                .bookableRoomsForPeriod(request.checkInDate(), request.checkOutDate()).stream()
                .map(RoomLookupResponse::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<Room> selected = new ArrayList<>();
        for (RoomRequest roomRequest : request.rooms()) {
            Room room = roomRepository
                    .findById(roomRequest.roomId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
            if (!seen.add(room.getId())) {
                throw new WalkInReviewException(
                        WalkInReviewException.Reason.DUPLICATE_ROOM,
                        "A room can only be selected once", room.getRoomNumber());
            }
            if (!bookable.contains(room.getId())) {
                throw new WalkInReviewException(
                        WalkInReviewException.Reason.ROOM_UNAVAILABLE,
                        "Room " + room.getRoomNumber() + " is not available for the whole stay", room.getRoomNumber());
            }
            selected.add(room);
        }
        requireAdultCapacity(request.adultCount(), selected);
        List<CheckInRoomLine> rooms = request.rooms().stream()
                .map(roomRequest -> toPreviewRoomLine(roomRequest, request.checkInDate(), request.checkOutDate()))
                .toList();
        BigDecimal total = rooms.stream().map(CheckInRoomLine::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GuestDocumentResponse> passports = guestDocumentService.findPassports(guest.getId());
        return new OtaEntryReviewResponse(
                guest.getId(),
                guestMapper.toLookupResponse(guest).fullName(),
                guest.getGuestCode(),
                !passports.isEmpty(),
                passports.isEmpty() ? null : passports.get(0).id(),
                request.source(),
                request.otaBookingReference(),
                request.checkInDate(),
                request.checkOutDate(),
                rooms,
                total,
                request.currency());
    }

    /**
     * Builds the read-only Walk-in Review without persisting anything.
     *
     * @param request submitted Walk-in guest/room/date selections
     * @return the Walk-in preview, including the calculated total
     * @throws ResponseStatusException if the Guest does not exist
     */
    @Transactional(readOnly = true)
    public WalkInReviewResponse reviewWalkIn(WalkInRequest request) {
        Guest guest = guestRepository
                .findById(request.guestId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found"));
        LocalDate checkInDate = LocalDate.now(clock);
        requireWalkInSelectionsValid(request, checkInDate);
        List<CheckInRoomLine> rooms = request.rooms().stream()
                .map(roomRequest -> toPreviewRoomLine(roomRequest, checkInDate, request.checkOutDate()))
                .toList();
        BigDecimal total = rooms.stream().map(CheckInRoomLine::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GuestDocumentResponse> passports = guestDocumentService.findPassports(guest.getId());
        UUID firstPassportDocumentId = passports.isEmpty() ? null : passports.get(0).id();
        return new WalkInReviewResponse(
                guest.getId(),
                guestMapper.toLookupResponse(guest).fullName(),
                guest.getGuestCode(),
                !passports.isEmpty(),
                firstPassportDocumentId,
                checkInDate,
                request.checkOutDate(),
                Instant.now(clock),
                rooms,
                total,
                request.currency());
    }

    /**
     * Lists the Rooms offered by the Walk-in Room Selection table: exactly the check-in-ready Rooms of
     * {@link #availableRoomsForWalkIn}, enriched with their Room Type name and adult capacity for display. This list
     * is UI guidance only; {@link #reviewWalkIn} and the final confirmation independently re-validate.
     *
     * @param checkOutDate staff-requested check-out date
     * @return the selectable Rooms, ordered by room number; empty when the check-out date is not after today
     */
    @Transactional(readOnly = true)
    public List<WalkInRoomOption> walkInRoomOptions(LocalDate checkOutDate) {
        if (!checkOutDate.isAfter(LocalDate.now(clock))) {
            return List.of();
        }
        return toRoomOptions(availableRoomsForWalkIn(checkOutDate));
    }

    /**
     * Lists the Rooms offered by the OTA Booking Not Entered Room Selection table: bookable inventory without an
     * inventory conflict for the whole staff-entered period {@code [checkInDate, checkOutDate)}
     * ({@link RoomAvailabilityService#bookableRoomsForPeriod}). Unlike Walk-in, today's operational readiness is not
     * a filter, so a Room that is currently OCCUPIED, DIRTY or CLEANING may be offered for a future arrival. This is
     * UI guidance only; {@link #reviewOtaEntry} and the Confirm inside {@link #createOtaEntry} re-validate.
     *
     * @param checkInDate inclusive OTA check-in date
     * @param checkOutDate exclusive OTA check-out date
     * @return the selectable Rooms, ordered by room number; empty when the period is not at least one night
     */
    @Transactional(readOnly = true)
    public List<WalkInRoomOption> otaRoomOptions(LocalDate checkInDate, LocalDate checkOutDate) {
        if (!checkOutDate.isAfter(checkInDate)) {
            return List.of();
        }
        return toRoomOptions(roomAvailability.bookableRoomsForPeriod(checkInDate, checkOutDate));
    }

    /** Enriches Room lookups with their Room Type name and adult capacity for a Room Selection table. */
    private List<WalkInRoomOption> toRoomOptions(List<RoomLookupResponse> ready) {
        Map<UUID, Room> roomsById = new HashMap<>();
        roomRepository.findAllById(ready.stream().map(RoomLookupResponse::id).toList())
                .forEach(room -> roomsById.put(room.getId(), room));
        return ready.stream()
                .map(lookup -> {
                    Room room = roomsById.get(lookup.id());
                    RoomType type = room == null ? null : room.getRoomType();
                    return new WalkInRoomOption(
                            lookup.id(),
                            lookup.roomNumber(),
                            type == null ? null : type.getName(),
                            type == null ? null : type.getCapacity(),
                            lookup.status());
                })
                .toList();
    }

    /**
     * Applies the read-only Walk-in rules that the authoritative create/confirm/check-in path enforces later, so a
     * rejected selection is reported on the form instead of on the Summary: the stay must be at least one night, every
     * selected Room must be distinct and check-in-ready and conflict-free for the whole stay (the same predicate as
     * the Room list), and the adults must fit the selected Rooms' adult capacity ({@link AdultCapacityRules}).
     *
     * @param request the submitted Walk-in selections
     * @param checkInDate the hotel current date
     * @throws WalkInReviewException when a rule is not satisfied
     */
    private void requireWalkInSelectionsValid(WalkInRequest request, LocalDate checkInDate) {
        if (!request.checkOutDate().isAfter(checkInDate)) {
            throw new WalkInReviewException(
                    WalkInReviewException.Reason.CHECK_OUT_NOT_AFTER_CHECK_IN,
                    "Check-out date must be after the check-in date");
        }
        Set<UUID> seen = new HashSet<>();
        List<Room> selected = new ArrayList<>();
        for (RoomRequest roomRequest : request.rooms()) {
            Room room = roomRepository
                    .findById(roomRequest.roomId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
            if (!seen.add(room.getId())) {
                throw new WalkInReviewException(
                        WalkInReviewException.Reason.DUPLICATE_ROOM,
                        "A room can only be selected once", room.getRoomNumber());
            }
            if (!roomAvailability.isCheckInReadyForPeriod(room, checkInDate, request.checkOutDate())) {
                throw new WalkInReviewException(
                        WalkInReviewException.Reason.ROOM_UNAVAILABLE,
                        "Room " + room.getRoomNumber() + " is not available for the whole stay", room.getRoomNumber());
            }
            selected.add(room);
        }
        requireAdultCapacity(request.adultCount(), selected);
    }

    /**
     * Applies the shared {@link AdultCapacityRules} to the selected Rooms and reports a failure as a reviewable
     * rejection. Nothing has been mutated when this is called.
     */
    private void requireAdultCapacity(Integer adultCount, List<Room> selected) {
        AdultCapacityRules.Result capacity = AdultCapacityRules.evaluate(adultCount, selected);
        switch (capacity.outcome()) {
            case INSUFFICIENT_ADULT_CAPACITY -> throw new WalkInReviewException(
                    WalkInReviewException.Reason.INSUFFICIENT_ADULT_CAPACITY,
                    "Reservation has " + capacity.adultCount() + " adults but the selected rooms support only "
                            + capacity.totalAdultCapacity() + " adults",
                    capacity.adultCount(), capacity.totalAdultCapacity());
            case CAPACITY_NOT_CONFIGURED -> throw new WalkInReviewException(
                    WalkInReviewException.Reason.CAPACITY_NOT_CONFIGURED,
                    "Room capacity is not configured for room type " + String.join(", ", capacity.unconfiguredRoomTypes()),
                    String.join(", ", capacity.unconfiguredRoomTypes()));
            case VALID -> { }
        }
    }

    /**
     * Executes the complete Walk-in "Confirm &amp; Check-in" operation as one atomic business
     * transaction: create the DIRECT Reservation, confirm it (which locks and re-validates Room
     * availability exactly as the existing Reservation Confirm operation does), then check it in
     * (which locks Rooms again, creates the Stay, and creates the ROOM Charges). If any step
     * fails, the whole operation rolls back since every step runs inside this one transaction.
     *
     * @param request submitted Walk-in guest/room/date selections
     * @return the Reservation response after Check-in
     */
    @Transactional
    public Response confirmWalkIn(WalkInRequest request) {
        LocalDate checkInDate = LocalDate.now(clock);
        CreateRequest createRequest = new CreateRequest(
                request.guestId(),
                checkInDate,
                request.checkOutDate(),
                request.adultCount(),
                request.childCount(),
                BookingSource.DIRECT,
                null,
                request.currency(),
                request.notes(),
                request.rooms(),
                List.of());
        Response created = reservationService.create(createRequest);
        reservationService.confirm(created.id());
        return reservationService.checkIn(created.id());
    }

    /**
     * Converts one assigned-room snapshot into its Check-in Review line.
     *
     * @param reservationRoom assigned-room snapshot
     * @return the Review room line
     */
    private CheckInRoomLine toRoomLine(ReservationRoom reservationRoom) {
        Room room = reservationRoom.getRoom();
        long nights = ChronoUnit.DAYS.between(reservationRoom.getCheckInDate(), reservationRoom.getCheckOutDate());
        return new CheckInRoomLine(
                room.getId(),
                room.getRoomNumber(),
                room.getRoomType() == null ? null : room.getRoomType().getName(),
                reservationRoom.getCheckInDate(),
                reservationRoom.getCheckOutDate(),
                reservationRoom.getNightlyRate(),
                nights,
                reservationRoom.getTotalAmount(),
                room.getRoomType() == null ? null : room.getRoomType().getCapacity(),
                room.getStatus().name());
    }

    /**
     * Builds one not-yet-persisted Walk-in preview room line from a submitted room selection.
     *
     * @param roomRequest submitted room identifier and nightly rate
     * @param checkInDate the hotel current date used as check-in date
     * @param checkOutDate the staff-selected check-out date
     * @return the preview room line
     * @throws ResponseStatusException if the Room does not exist
     */
    private CheckInRoomLine toPreviewRoomLine(RoomRequest roomRequest, LocalDate checkInDate, LocalDate checkOutDate) {
        Room room = roomRepository
                .findById(roomRequest.roomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        BigDecimal total = roomRequest.nightlyRate().multiply(BigDecimal.valueOf(Math.max(nights, 0)));
        return new CheckInRoomLine(
                room.getId(),
                room.getRoomNumber(),
                room.getRoomType() == null ? null : room.getRoomType().getName(),
                checkInDate,
                checkOutDate,
                roomRequest.nightlyRate(),
                nights,
                total,
                room.getRoomType() == null ? null : room.getRoomType().getCapacity(),
                room.getStatus().name());
    }
}
