package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.StayExtensionFormResponse;
import com.example.hotel.dto.booking.response.StayExtensionSummaryResponse;
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
        this.clock = clock;
    }

    /**
     * Builds the read-only context of the Extend Stay form. Nothing is locked or changed.
     *
     * @param reservationId Reservation identifier
     * @param includeOutstanding whether the caller may see payment data (the outstanding balance is then included)
     * @return the form context
     * @throws StayExtensionException if the Reservation/Stay is not extendable
     */
    @Transactional(readOnly = true)
    public StayExtensionFormResponse form(UUID reservationId, boolean includeOutstanding) {
        Reservation reservation = load(reservationId);
        Stay stay = requireActiveStay(reservation, stays.findByReservationId(reservationId));
        List<StayRoomAssignment> open = assignments.findOpenByStayIdWithLineage(stay.getId());
        if (open.isEmpty()) {
            throw new StayExtensionException(Reason.NO_CURRENT_ROOM, "Stay has no current room");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate next = reservation.getCheckOutDate().plusDays(1);
        LocalDate earliest = next.isBefore(today) ? today : next;
        Guest guest = reservation.getGuest();
        List<StayExtensionFormResponse.Line> lines = open.stream()
                .map(a -> new StayExtensionFormResponse.Line(
                        a.getRoom().getRoomNumber(),
                        a.getRoom().getRoomType() == null ? null : a.getRoom().getRoomType().getName(),
                        a.getOriginalReservationRoom().getNightlyRate()))
                .toList();
        return new StayExtensionFormResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                (guest.getFirstName() + " " + guest.getLastName()).trim(),
                guest.getGuestCode(),
                reservation.getCurrency(),
                reservation.getCheckOutDate(),
                earliest,
                lines,
                includeOutstanding ? stayBalanceService.calculate(stay.getId()).outstanding() : null);
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
        // Approved rule: strictly later than the current planned check-out AND not before hotel today. An overdue stay
        // may therefore extend exactly to today; the period is always [previous, new).
        if (newDate == null || !newDate.isAfter(previous) || newDate.isBefore(today)) {
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
