package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayAmountRow;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.entity.booking.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-model for the Front Desk workspace. It only composes existing domain information: Arrival Readiness comes
 * from {@link ArrivalReadinessRules}, financial departure readiness from {@link DepartureReadinessRules}, and
 * current Rooms from OPEN StayRoomAssignments. Every method uses a fixed number of batch queries, never one query
 * per row, and nothing is persisted or mutated. Authorization is enforced by the controller; this service
 * only receives whether monetary amounts may be included.
 */
@Service
public class FrontDeskQueryService {

    private final ReservationRepository reservations;
    private final StayRepository stays;
    private final StayRoomAssignmentRepository assignments;
    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final GuestMapper guestMapper;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reservations source of pending arrivals and their booked rooms
     * @param stays source of checked-in Stays
     * @param assignments source of current (open) room assignments
     * @param charges source of grouped Charge totals
     * @param payments source of grouped PAID Payment totals
     * @param guestMapper mapper that builds the Guest display name
     * @param clock authoritative hotel business clock
     */
    public FrontDeskQueryService(
            ReservationRepository reservations,
            StayRepository stays,
            StayRoomAssignmentRepository assignments,
            ChargeRepository charges,
            PaymentRepository payments,
            GuestMapper guestMapper,
            Clock clock) {
        this.reservations = reservations;
        this.stays = stays;
        this.assignments = assignments;
        this.charges = charges;
        this.payments = payments;
        this.guestMapper = guestMapper;
        this.clock = clock;
    }

    /**
     * Returns the hotel current date used by every Front Desk view.
     *
     * @return the hotel current date from the injected Clock
     */
    public LocalDate hotelToday() {
        return LocalDate.now(clock);
    }

    /**
     * Loads Arrivals: CONFIRMED Reservations with a check-in date on or before the hotel date that have no Stay yet
     * (three queries: reservations with guest, booked rooms with room type, existing Stays). Passport presence is not
     * evaluated here (it is only a WARNING and stays on the Check-in Review). Ordered needs-attention overdue first,
     * then other needs-attention, then ready; ties by check-in date then Reservation number.
     *
     * @return the ordered arrival rows
     */
    @Transactional(readOnly = true)
    public List<FrontDeskArrivalRow> arrivals() {
        LocalDate today = hotelToday();
        List<Reservation> pending = reservations.findByStatusAndCheckInOnOrBefore(ReservationStatus.CONFIRMED, today);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = pending.stream().map(Reservation::getId).toList();
        Map<UUID, List<Room>> roomsByReservation = new HashMap<>();
        for (Object[] row : reservations.findBookedRoomsByReservationIdIn(ids)) {
            roomsByReservation.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add((Room) row[1]);
        }
        Set<UUID> withStay = new HashSet<>(stays.findReservationIdsWithStay(ids));
        List<FrontDeskArrivalRow> rows = new ArrayList<>();
        for (Reservation reservation : pending) {
            if (withStay.contains(reservation.getId())) {
                continue;
            }
            List<Room> rooms = roomsByReservation.getOrDefault(reservation.getId(), List.of());
            ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                    reservation.getStatus(), reservation.getCheckInDate(), today, false, reservation.getAdultCount(), rooms, true);
            rows.add(toArrivalRow(reservation, rooms, readiness));
        }
        rows.sort(Comparator.comparingInt(FrontDeskQueryService::arrivalRank)
                .thenComparing(FrontDeskArrivalRow::checkInDate)
                .thenComparing(FrontDeskArrivalRow::reservationNumber));
        return rows;
    }

    /**
     * Loads Departures: CHECKED_IN Stays (of CHECKED_IN Reservations) whose planned check-out date is on or before
     * the hotel date (four queries: stays with reservation and guest, open assignments, grouped charges, grouped PAID
     * payments). Ordered overdue first, then payment required, then ready; ties by check-out date then Reservation
     * number.
     *
     * @param includeAmounts whether the outstanding amount may be included (user holds MANAGE_PAYMENT)
     * @return the ordered departure rows
     */
    @Transactional(readOnly = true)
    public List<FrontDeskStayRow> departures(boolean includeAmounts) {
        LocalDate today = hotelToday();
        List<Stay> due = stays.findWithReservationAndGuestDueBy(
                StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN, today);
        List<FrontDeskStayRow> rows = toStayRows(due, today, true, includeAmounts);
        rows.sort(Comparator.comparingInt(FrontDeskQueryService::departureRank)
                .thenComparing(FrontDeskStayRow::plannedCheckOutDate)
                .thenComparing(FrontDeskStayRow::reservationNumber));
        return rows;
    }

    /**
     * Loads In-house: every CHECKED_IN Stay of a CHECKED_IN Reservation (two queries: stays with reservation and
     * guest, open assignments). Ordered by the first current room number then Reservation number. No money is read.
     *
     * @return the in-house rows
     */
    @Transactional(readOnly = true)
    public List<FrontDeskStayRow> inHouse() {
        LocalDate today = hotelToday();
        List<Stay> current = stays.findWithReservationAndGuest(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN);
        List<FrontDeskStayRow> rows = toStayRows(current, today, false, false);
        rows.sort(Comparator
                .comparing((FrontDeskStayRow row) -> row.rooms().isEmpty() ? "" : row.rooms().get(0).roomNumber())
                .thenComparing(FrontDeskStayRow::reservationNumber));
        return rows;
    }

    private List<FrontDeskStayRow> toStayRows(List<Stay> source, LocalDate today, boolean withBalance, boolean includeAmounts) {
        if (source.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> stayIds = source.stream().map(Stay::getId).toList();
        Map<UUID, List<FrontDeskRoomResponse>> roomsByStay = new HashMap<>();
        for (StayRoomAssignment assignment : assignments.findOpenByStayIdInWithRoom(stayIds)) {
            Room room = assignment.getRoom();
            roomsByStay.computeIfAbsent(assignment.getStay().getId(), key -> new ArrayList<>()).add(
                    new FrontDeskRoomResponse(room.getId(), room.getRoomNumber(), room.getRoomType().getName(), null));
        }
        Map<UUID, BigDecimal> chargeTotals = withBalance ? totals(charges.sumAmountByStayIdIn(stayIds)) : Map.of();
        Map<UUID, BigDecimal> paidTotals = withBalance
                ? totals(payments.sumAppliedAmountByStayIdInAndStatus(stayIds, PaymentStatus.PAID))
                : Map.of();
        List<FrontDeskStayRow> rows = new ArrayList<>();
        for (Stay stay : source) {
            Reservation reservation = stay.getReservation();
            BigDecimal outstanding = DepartureReadinessRules.outstanding(
                    chargeTotals.getOrDefault(stay.getId(), BigDecimal.ZERO),
                    paidTotals.getOrDefault(stay.getId(), BigDecimal.ZERO));
            boolean paymentRequired = withBalance
                    && DepartureReadinessRules.readiness(outstanding)
                            == DepartureReadinessRules.FinancialReadiness.PAYMENT_REQUIRED;
            boolean overdue = OverdueDeparture.isOverdue(reservation.getCheckOutDate(), today);
            long overdueDays = OverdueDeparture.overdueDays(reservation.getCheckOutDate(), today);
            Guest guest = reservation.getGuest();
            rows.add(new FrontDeskStayRow(
                    reservation.getId(),
                    reservation.getReservationNumber(),
                    guestName(guest),
                    guest.getGuestCode(),
                    roomsByStay.getOrDefault(stay.getId(), List.of()),
                    stay.getActualCheckInAt(),
                    reservation.getCheckOutDate(),
                    overdue,
                    overdueDays,
                    paymentRequired,
                    withBalance && (overdue || paymentRequired),
                    includeAmounts ? outstanding : null,
                    reservation.getCurrency()));
        }
        return rows;
    }

    private FrontDeskArrivalRow toArrivalRow(Reservation reservation, List<Room> rooms, ArrivalReadiness readiness) {
        Map<UUID, ArrivalIssueCode> blockerByRoom = new LinkedHashMap<>();
        for (ArrivalReadinessIssue issue : readiness.issues()) {
            if (issue.severity() == ArrivalIssueSeverity.BLOCKER && issue.roomId() != null) {
                blockerByRoom.put(issue.roomId(), issue.code());
            }
        }
        List<FrontDeskRoomResponse> roomRows = rooms.stream()
                .map(room -> new FrontDeskRoomResponse(
                        room.getId(), room.getRoomNumber(), room.getRoomType().getName(), blockerByRoom.get(room.getId())))
                .toList();
        boolean housekeeping = blockerByRoom.values().stream()
                .anyMatch(code -> code == ArrivalIssueCode.ROOM_DIRTY || code == ArrivalIssueCode.ROOM_CLEANING);
        boolean overdue = readiness.timing() == CheckInTiming.LATE;
        boolean hasBlocker = !readiness.blockers().isEmpty();
        Guest guest = reservation.getGuest();
        return new FrontDeskArrivalRow(
                reservation.getId(),
                reservation.getReservationNumber(),
                guestName(guest),
                guest.getGuestCode(),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getCheckInDate(),
                overdue,
                overdue || hasBlocker,
                readiness,
                roomRows,
                housekeeping,
                EffectiveBookingContact.of(reservation).phone());
    }

    private static int arrivalRank(FrontDeskArrivalRow row) {
        if (!row.needsAttention()) {
            return 2;
        }
        return row.overdue() ? 0 : 1;
    }

    private static int departureRank(FrontDeskStayRow row) {
        if (row.overdue()) {
            return 0;
        }
        return row.paymentRequired() ? 1 : 2;
    }

    private static Map<UUID, BigDecimal> totals(List<StayAmountRow> rows) {
        return rows.stream().collect(Collectors.toMap(StayAmountRow::stayId, StayAmountRow::amount));
    }

    private String guestName(Guest guest) {
        String fullName = guestMapper.toLookupResponse(guest).fullName();
        return fullName == null || fullName.isBlank() ? null : fullName.trim();
    }
}
