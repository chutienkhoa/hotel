package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse;
import com.example.hotel.dto.booking.response.StayExtensionSummaryResponse;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayExtension;
import com.example.hotel.entity.booking.StayExtensionRoom;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayExtensionRepository;
import com.example.hotel.repository.booking.StayExtensionRoomRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomImageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Extends the planned check-out of a CHECKED_IN Stay (whole Stay only). The original {@link ReservationRoom} snapshot
 * and {@code Reservation.totalAmount} are never touched; {@code Reservation.checkOutDate} becomes the current planned
 * departure, and each open room lineage gets a {@link StayExtensionRoom} line and one ROOM {@link Charge}. Lock order:
 * Stay, then the current rooms sorted by id, then re-read and validate, then mutate, all in one transaction.
 */
@Service
public class StayExtensionService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Days after the current planned check-out covered by the calendar's availability states (about two months). */
    private static final int AVAILABILITY_WINDOW_DAYS = 62;

    private final ReservationRepository reservations;
    private final StayRepository stays;
    private final StayRoomAssignmentRepository assignments;
    private final RoomRepository rooms;
    private final ChargeRepository charges;
    private final StayExtensionRepository extensions;
    private final StayExtensionRoomRepository extensionRooms;
    private final AuditLogRepository audits;
    private final ReservationMapper reservationMapper;
    private final RoomAvailabilityService roomAvailability;
    private final StayBalanceService stayBalanceService;
    private final RoomImageService roomImages;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reservations Reservation repository
     * @param stays Stay repository (Stay row lock)
     * @param assignments room-assignment repository (current rooms and lineage)
     * @param rooms Room repository (room row locks)
     * @param charges Charge repository (extension ROOM charges)
     * @param extensions extension-event repository
     * @param extensionRooms extension-line repository
     * @param audits audit repository
     * @param reservationMapper response mapper
     * @param roomAvailability shared inventory primitive
     * @param stayBalanceService outstanding balance for the form (only when the caller may see it)
     * @param roomImages primary room images for the read-only screens
     * @param clock authoritative hotel business clock
     */
    public StayExtensionService(
            ReservationRepository reservations,
            StayRepository stays,
            StayRoomAssignmentRepository assignments,
            RoomRepository rooms,
            ChargeRepository charges,
            StayExtensionRepository extensions,
            StayExtensionRoomRepository extensionRooms,
            AuditLogRepository audits,
            ReservationMapper reservationMapper,
            RoomAvailabilityService roomAvailability,
            StayBalanceService stayBalanceService,
            RoomImageService roomImages,
            Clock clock) {
        this.reservations = reservations;
        this.stays = stays;
        this.assignments = assignments;
        this.rooms = rooms;
        this.charges = charges;
        this.extensions = extensions;
        this.extensionRooms = extensionRooms;
        this.audits = audits;
        this.reservationMapper = reservationMapper;
        this.roomAvailability = roomAvailability;
        this.stayBalanceService = stayBalanceService;
        this.roomImages = roomImages;
        this.clock = clock;
    }

    /**
     * Builds the read-only Select Extension model for one proposed new check-out date. Nothing is locked or changed.
     * An invalid date or a room conflict is reported as a {@link StayExtensionPreviewResponse.State}, not thrown, so the
     * screen can explain it; only an ineligible Reservation/Stay is thrown.
     *
     * @param reservationId Reservation identifier
     * @param requestedCheckOutDate proposed new check-out date, or {@code null} when none has been selected
     * @param includeOutstanding whether the caller may see payment data (folio impact is then included)
     * @return the preview model
     * @throws StayExtensionException if the Reservation/Stay is not extendable
     */
    @Transactional(readOnly = true)
    public StayExtensionPreviewResponse preview(
            UUID reservationId, LocalDate requestedCheckOutDate, boolean includeOutstanding) {
        Reservation reservation = load(reservationId);
        Stay stay = requireActiveStay(reservation, stays.findByReservationId(reservationId));
        List<StayRoomAssignment> open = assignments.findOpenByStayIdWithLineage(stay.getId());
        if (open.isEmpty()) {
            throw new StayExtensionException(Reason.NO_CURRENT_ROOM, "Stay has no current room");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate previous = reservation.getCheckOutDate();
        LocalDate next = previous.plusDays(1);
        LocalDate earliest = next.isBefore(today) ? today : next;
        LocalDate checkIn = reservation.getCheckInDate();
        boolean valid = isValidNewCheckOut(previous, requestedCheckOutDate, today);
        List<UUID> roomIds = open.stream().map(a -> a.getRoom().getId()).toList();
        Set<UUID> conflicted = valid
                ? roomAvailability.conflictedRoomIds(roomIds, previous, requestedCheckOutDate, stay.getId())
                : Set.of();
        Set<UUID> withImage = roomImages.roomIdsWithPrimaryImage(roomIds);
        long additionalNights = valid ? ChronoUnit.DAYS.between(previous, requestedCheckOutDate) : 0;

        BigDecimal extensionAmount = null;
        List<StayExtensionPreviewResponse.Room> lines = new ArrayList<>();
        for (StayRoomAssignment assignment : open) {
            Room room = assignment.getRoom();
            ReservationRoom lineage = assignment.getOriginalReservationRoom();
            BigDecimal amount = valid ? lineage.getNightlyRate().multiply(BigDecimal.valueOf(additionalNights)) : null;
            if (amount != null) {
                extensionAmount = extensionAmount == null ? amount : extensionAmount.add(amount);
            }
            lines.add(new StayExtensionPreviewResponse.Room(
                    room.getId(),
                    room.getRoomNumber(),
                    room.getRoomType() == null ? null : room.getRoomType().getName(),
                    lineage.getNightlyRate(),
                    amount,
                    conflicted.contains(room.getId()),
                    withImage.contains(room.getId())));
        }

        StayExtensionPreviewResponse.State state = requestedCheckOutDate == null
                ? StayExtensionPreviewResponse.State.NOT_SELECTED
                : !valid
                        ? StayExtensionPreviewResponse.State.INVALID_DATE
                        : conflicted.isEmpty()
                                ? StayExtensionPreviewResponse.State.AVAILABLE
                                : StayExtensionPreviewResponse.State.ROOM_CONFLICT;

        // Accommodation totals reuse the extension summary so the figures match Reservation Detail exactly.
        BigDecimal accommodation = summary(reservationId).currentAccommodationTotal();
        BigDecimal added = extensionAmount == null ? BigDecimal.ZERO : extensionAmount;

        StayExtensionPreviewResponse.Folio folio = null;
        if (includeOutstanding) {
            StayBalance balance = stayBalanceService.calculate(stay.getId());
            folio = new StayExtensionPreviewResponse.Folio(
                    balance.totalCharges(),
                    balance.totalPaidPayments(),
                    balance.outstanding(),
                    DepartureReadinessRules.outstanding(balance.totalCharges().add(added), balance.totalPaidPayments()));
        }
        Guest guest = reservation.getGuest();
        LocalDate availabilityKnownUntil = previous.plusDays(AVAILABILITY_WINDOW_DAYS);
        LocalDate firstUnavailable = firstUnavailableCheckOut(roomIds, previous, availabilityKnownUntil, stay.getId());
        return new StayExtensionPreviewResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                (guest.getFirstName() + " " + guest.getLastName()).trim(),
                guest.getGuestCode(),
                reservation.getSource(),
                reservation.getAdultCount(),
                reservation.getChildCount(),
                reservation.getCurrency(),
                checkIn,
                previous,
                earliest,
                requestedCheckOutDate,
                state,
                ChronoUnit.DAYS.between(checkIn, previous),
                additionalNights,
                ChronoUnit.DAYS.between(checkIn, valid ? requestedCheckOutDate : previous),
                List.copyOf(lines),
                extensionAmount,
                accommodation,
                accommodation.add(added),
                folio,
                firstUnavailable,
                availabilityKnownUntil);
    }

    /**
     * Validates a proposed extension for the Review step without locking or writing. It applies the same staleness,
     * date and availability rules as {@link #extend}, so a rejected proposal is reported through the same reasons; the
     * final Confirm still re-validates under lock and does not trust this result.
     *
     * @param reservationId Reservation identifier
     * @param request expected current check-out and requested new check-out
     * @param includeOutstanding whether the caller may see payment data
     * @return the preview of an extension that may be confirmed
     * @throws StayExtensionException if the Stay changed, the date is invalid, or any room conflicts
     */
    @Transactional(readOnly = true)
    public StayExtensionPreviewResponse review(
            UUID reservationId, StayExtensionRequest request, boolean includeOutstanding) {
        StayExtensionPreviewResponse preview = preview(reservationId, request.newCheckOutDate(), includeOutstanding);
        if (request.expectedCurrentCheckOutDate() == null
                || !preview.currentCheckOutDate().equals(request.expectedCurrentCheckOutDate())) {
            throw new StayExtensionException(Reason.STALE_CHECK_OUT_DATE, "Planned check-out changed; reload and retry");
        }
        if (preview.state() == StayExtensionPreviewResponse.State.AVAILABLE) {
            return preview;
        }
        if (preview.state() == StayExtensionPreviewResponse.State.ROOM_CONFLICT) {
            String numbers = preview.rooms().stream()
                    .filter(StayExtensionPreviewResponse.Room::conflicted)
                    .map(StayExtensionPreviewResponse.Room::roomNumber)
                    .collect(Collectors.joining(", "));
            throw new StayExtensionException(Reason.INVENTORY_CONFLICT, "Room is already booked for these dates", numbers);
        }
        throw new StayExtensionException(
                Reason.INVALID_NEW_CHECK_OUT_DATE,
                "New check-out must be after " + preview.currentCheckOutDate() + " and not before "
                        + LocalDate.now(clock),
                preview.currentCheckOutDate().format(DATE_FORMAT),
                LocalDate.now(clock).format(DATE_FORMAT));
    }

    /**
     * Returns the primary image of a current room of an active Stay, for the read-only extension screens only.
     *
     * @param reservationId Reservation identifier
     * @param roomId a room currently assigned to the Stay
     * @return the room's primary image
     * @throws ResponseStatusException if the room is not a current room of the Stay or has no primary image
     */
    @Transactional(readOnly = true)
    public RoomImageFile currentRoomImage(UUID reservationId, UUID roomId) {
        Stay stay = requireActiveStay(load(reservationId), stays.findByReservationId(reservationId));
        boolean current = assignments.findOpenByStayIdWithLineage(stay.getId()).stream()
                .anyMatch(a -> a.getRoom().getId().equals(roomId));
        if (!current) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room image");
        }
        return roomImages.loadPrimaryImage(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room image"));
    }

    /**
     * Reads the extension history and the derived accommodation totals of a Reservation in two queries.
     *
     * @param reservationId Reservation identifier
     * @return the summary (empty history when never extended)
     */
    @Transactional(readOnly = true)
    public StayExtensionSummaryResponse summary(UUID reservationId) {
        Reservation reservation = load(reservationId);
        var lines = extensionRooms.findByReservationId(reservationId);
        BigDecimal extensionAmount = BigDecimal.ZERO;
        List<StayExtensionSummaryResponse.Event> events = new ArrayList<>();
        List<StayExtensionSummaryResponse.Line> current = new ArrayList<>();
        StayExtension last = null;
        for (StayExtensionRoom line : lines) {
            StayExtension event = line.getExtension();
            if (last != null && !last.getId().equals(event.getId())) {
                events.add(toEvent(last, current));
                current = new ArrayList<>();
            }
            last = event;
            current.add(new StayExtensionSummaryResponse.Line(
                    line.getRoom().getRoomNumber(),
                    line.getNightlyRate(),
                    ChronoUnit.DAYS.between(line.getFromDate(), line.getToDate()),
                    line.getAmount()));
            extensionAmount = extensionAmount.add(line.getAmount());
        }
        if (last != null) {
            events.add(toEvent(last, current));
        }
        return new StayExtensionSummaryResponse(
                reservation.getTotalAmount(),
                extensionAmount,
                reservation.getTotalAmount().add(extensionAmount),
                List.copyOf(events));
    }

    /**
     * Extends the whole Stay to a later planned check-out date.
     *
     * @param reservationId Reservation identifier
     * @param request expected current check-out and requested new check-out
     * @return the Reservation response (status unchanged; total still the original booking total)
     * @throws StayExtensionException if any state, staleness, date, availability or amount rule fails
     */
    @Transactional
    public Response extend(UUID reservationId, StayExtensionRequest request) {
        CurrentUser user = currentUser();
        // 1. Lock the Stay first (same first lock as check-out and Room Change), then read the Reservation so its
        //    state is never older than the lock.
        var lockedStay = stays.findByReservationIdForUpdate(reservationId);
        Reservation reservation = load(reservationId);
        Stay stay = requireActiveStay(reservation, lockedStay);

        // 2. Read the current rooms, lock them sorted by id, and re-read under the lock.
        List<UUID> roomIds = assignments.findOpenByStayIdWithLineage(stay.getId()).stream()
                .map(a -> a.getRoom().getId())
                .sorted()
                .toList();
        if (roomIds.isEmpty()) {
            throw new StayExtensionException(Reason.NO_CURRENT_ROOM, "Stay has no current room");
        }
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }
        List<StayRoomAssignment> open = assignments.findOpenByStayIdWithLineage(stay.getId());
        Set<UUID> openRoomIds = open.stream().map(a -> a.getRoom().getId()).collect(Collectors.toSet());
        if (open.isEmpty() || !openRoomIds.equals(Set.copyOf(roomIds))) {
            throw new StayExtensionException(Reason.STALE_CHECK_OUT_DATE, "Stay rooms changed; reload and retry");
        }

        // 3. Revalidate the planned check-out and the requested date.
        LocalDate previous = reservation.getCheckOutDate();
        if (request.expectedCurrentCheckOutDate() == null || !previous.equals(request.expectedCurrentCheckOutDate())) {
            throw new StayExtensionException(Reason.STALE_CHECK_OUT_DATE, "Planned check-out changed; reload and retry");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate newDate = request.newCheckOutDate();
        // The period is always [previous, new); an overdue stay may therefore extend exactly to today.
        if (!isValidNewCheckOut(previous, newDate, today)) {
            throw new StayExtensionException(
                    Reason.INVALID_NEW_CHECK_OUT_DATE,
                    "New check-out must be after " + previous + " and not before " + today,
                    previous.format(DATE_FORMAT), today.format(DATE_FORMAT));
        }

        // 4. Amounts are computed before anything is written; every line must be positive.
        long nights = ChronoUnit.DAYS.between(previous, newDate);
        List<BigDecimal> amounts = new ArrayList<>();
        for (StayRoomAssignment assignment : open) {
            BigDecimal amount = assignment.getOriginalReservationRoom().getNightlyRate().multiply(BigDecimal.valueOf(nights));
            if (amount.signum() <= 0) {
                throw new StayExtensionException(Reason.NON_POSITIVE_AMOUNT, "Extension amount must be positive");
            }
            amounts.add(amount);
        }

        // 5. Inventory, with this Stay's own allocation ignored. Conflicts reject; nothing is moved.
        Set<UUID> conflicted = roomAvailability.conflictedRoomIds(roomIds, previous, newDate, stay.getId());
        if (!conflicted.isEmpty()) {
            String numbers = open.stream()
                    .filter(a -> conflicted.contains(a.getRoom().getId()))
                    .map(a -> a.getRoom().getRoomNumber())
                    .collect(Collectors.joining(", "));
            throw new StayExtensionException(
                    Reason.INVENTORY_CONFLICT, "Room is already booked for these dates", numbers);
        }

        // 6. Mutate atomically.
        try {
            reservation.extendCheckOut(newDate);
        } catch (IllegalStateException exception) {
            throw new StayExtensionException(Reason.RESERVATION_NOT_CHECKED_IN, exception.getMessage());
        }
        reservation.audit(user.id());
        StayExtension extension = new StayExtension(stay, extensions.findLastSequenceNo(stay.getId()) + 1, previous, newDate);
        extension.audit(user.id());
        extensions.save(extension);

        BigDecimal total = BigDecimal.ZERO;
        StringBuilder detail = new StringBuilder();
        for (int index = 0; index < open.size(); index++) {
            StayRoomAssignment assignment = open.get(index);
            ReservationRoom lineage = assignment.getOriginalReservationRoom();
            Room room = assignment.getRoom();
            BigDecimal amount = amounts.get(index);
            Charge charge = Charge.create(
                    stay,
                    ChargeType.ROOM,
                    "Room " + room.getRoomNumber() + " extension " + previous.format(DATE_FORMAT) + " - "
                            + newDate.format(DATE_FORMAT),
                    BigDecimal.valueOf(nights),
                    lineage.getNightlyRate(),
                    amount);
            charge.audit(user.id());
            charges.save(charge);
            StayExtensionRoom line = new StayExtensionRoom(extension, lineage, room, lineage.getNightlyRate(), amount, charge);
            line.audit(user.id());
            extensionRooms.save(line);
            total = total.add(amount);
            if (index > 0) {
                detail.append("; ");
            }
            detail.append(room.getRoomNumber()).append(':').append(lineage.getNightlyRate().toPlainString())
                    .append('x').append(nights).append('=').append(amount.toPlainString());
        }
        audits.save(new AuditLog(
                user.id(),
                "EXTEND_STAY",
                reservation.getId(),
                "checkOut=" + previous,
                "checkOut=" + newDate + "; nights=" + nights + "; extensionId=" + extension.getId() + "; rooms=["
                        + detail + "]; total=" + total.toPlainString()));
        return reservationMapper.toResponse(reservation);
    }

    /**
     * Finds the earliest checkout date in {@code (previous, windowEnd]} that the Stay's rooms cannot take. A later
     * checkout only extends the interval {@code [previous, out)}, so conflict is monotone and a binary search suffices.
     *
     * @param roomIds current rooms of the Stay
     * @param previous current planned check-out
     * @param windowEnd last checkout date of the window
     * @param stayId the Stay whose own allocation is ignored
     * @return the first conflicting checkout date, or {@code null} when none conflicts within the window
     */
    private LocalDate firstUnavailableCheckOut(List<UUID> roomIds, LocalDate previous, LocalDate windowEnd, UUID stayId) {
        if (roomAvailability.conflictedRoomIds(roomIds, previous, windowEnd, stayId).isEmpty()) {
            return null;
        }
        LocalDate low = previous.plusDays(1);
        LocalDate high = windowEnd;
        while (low.isBefore(high)) {
            LocalDate mid = low.plusDays(ChronoUnit.DAYS.between(low, high) / 2);
            if (roomAvailability.conflictedRoomIds(roomIds, previous, mid, stayId).isEmpty()) {
                low = mid.plusDays(1);
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * The approved date rule: strictly later than the current planned check-out AND not before hotel today.
     *
     * @param previous current planned check-out
     * @param newDate requested planned check-out, possibly {@code null}
     * @param today authoritative hotel date
     * @return {@code true} when the requested date may be confirmed
     */
    private static boolean isValidNewCheckOut(LocalDate previous, LocalDate newDate, LocalDate today) {
        return newDate != null && newDate.isAfter(previous) && !newDate.isBefore(today);
    }

    private StayExtensionSummaryResponse.Event toEvent(StayExtension event, List<StayExtensionSummaryResponse.Line> lines) {
        return new StayExtensionSummaryResponse.Event(
                event.getSequenceNo(), event.getPreviousCheckOutDate(), event.getNewCheckOutDate(), List.copyOf(lines));
    }

    private Stay requireActiveStay(Reservation reservation, java.util.Optional<Stay> stay) {
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new StayExtensionException(Reason.RESERVATION_NOT_CHECKED_IN, "Stay Extension requires a CHECKED_IN reservation");
        }
        Stay found = stay.orElseThrow(
                () -> new StayExtensionException(Reason.STAY_NOT_FOUND, "Stay not found for reservation"));
        if (found.getStatus() != StayStatus.CHECKED_IN) {
            throw new StayExtensionException(Reason.STAY_NOT_ACTIVE, "Stay is not CHECKED_IN");
        }
        return found;
    }

    private Reservation load(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }

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
}
