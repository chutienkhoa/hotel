package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInRoomLine;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.service.customer.GuestDocumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
     * @param roomRepository repository used for date-range-aware Walk-in room availability
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
            GuestRepository guestRepository,
            GuestMapper guestMapper,
            GuestDocumentService guestDocumentService,
            Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationQueryService = reservationQueryService;
        this.reservationService = reservationService;
        this.roomRepository = roomRepository;
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
        CheckInTiming timing = classify(today, reservation.getCheckInDate());
        boolean eligible = reservation.getStatus() == ReservationStatus.CONFIRMED && timing != CheckInTiming.EARLY;
        Guest guest = reservation.getGuest();
        boolean passportAvailable = guestDocumentService.hasPassport(guest.getId());
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
                reservation.getCurrency());
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
     * Lists active Rooms available for the entire requested date range, reusing the existing
     * overlap semantics from {@link ReservationRepository#hasOverlap}. This list is UI guidance
     * only; the final Walk-in confirmation independently re-locks and re-validates.
     *
     * @param checkInDate inclusive requested check-in date
     * @param checkOutDate exclusive requested check-out date
     * @return active Rooms with no overlapping CONFIRMED/CHECKED_IN Reservation for the range
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> availableRoomsForRange(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomRepository.findByActiveTrue().stream()
                .filter(room -> room.getStatus() != RoomStatus.OUT_OF_ORDER)
                .filter(room -> !reservationRepository.hasOverlap(
                        room.getId(),
                        checkInDate,
                        checkOutDate,
                        List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)))
                .map(room -> new RoomLookupResponse(
                        room.getId(), room.getRoomNumber(), room.getStatus().name(), room.isActive()))
                .toList();
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
                BookingSource.DIRECT,
                null,
                request.currency(),
                request.notes(),
                request.rooms());
        Response created = reservationService.create(createRequest);
        reservationService.confirm(created.id());
        return reservationService.checkIn(created.id());
    }

    /**
     * Classifies the hotel current date against a Reservation's planned check-in date.
     *
     * @param today authoritative hotel current date
     * @param checkInDate the Reservation's planned check-in date
     * @return the Early/Normal/Late classification
     */
    private CheckInTiming classify(LocalDate today, LocalDate checkInDate) {
        if (today.isBefore(checkInDate)) {
            return CheckInTiming.EARLY;
        }
        if (today.isAfter(checkInDate)) {
            return CheckInTiming.LATE;
        }
        return CheckInTiming.NORMAL;
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
                room.getRoomNumber(),
                room.getRoomType() == null ? null : room.getRoomType().getName(),
                reservationRoom.getCheckInDate(),
                reservationRoom.getCheckOutDate(),
                reservationRoom.getNightlyRate(),
                nights,
                reservationRoom.getTotalAmount());
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
                room.getRoomNumber(),
                room.getRoomType() == null ? null : room.getRoomType().getName(),
                checkInDate,
                checkOutDate,
                roomRequest.nightlyRate(),
                nights,
                total);
    }
}
