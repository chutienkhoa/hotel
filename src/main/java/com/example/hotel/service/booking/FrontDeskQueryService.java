package com.example.hotel.service.booking;

import com.example.hotel.common.SortWhitelist;
import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    /** Page size used by every paged Front Desk view (Batch 3A), consistent with the other list screens. */
    private static final int FRONT_DESK_PAGE_SIZE = 10;

    /** Allow-listed in-memory comparators for the Arrivals sortable columns, keyed by {@link TableSorts#FRONT_DESK_ARRIVALS}. */
    private static final Map<String, Comparator<FrontDeskArrivalRow>> ARRIVAL_SORTS = Map.of(
            "reservationNumber", Comparator.comparing(FrontDeskArrivalRow::reservationNumber),
            "guestName", Comparator.comparing(
                            (FrontDeskArrivalRow row) -> row.guestName() == null ? "" : row.guestName(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(FrontDeskArrivalRow::reservationNumber),
            "checkInDate", Comparator.comparing(FrontDeskArrivalRow::checkInDate)
                    .thenComparing(FrontDeskArrivalRow::reservationNumber));

    /**
     * Allow-listed in-memory comparators for the Departures/In-house sortable columns, keyed by
     * {@link TableSorts#FRONT_DESK_DEPARTURES} and {@link TableSorts#FRONT_DESK_IN_HOUSE}. Both views share this
     * single map: each view's own whitelist already restricts which of these keys it accepts.
     */
    private static final Map<String, Comparator<FrontDeskStayRow>> STAY_SORTS = Map.of(
            "reservationNumber", Comparator.comparing(FrontDeskStayRow::reservationNumber),
            "guestName", Comparator.comparing(
                            (FrontDeskStayRow row) -> row.guestName() == null ? "" : row.guestName(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(FrontDeskStayRow::reservationNumber),
            "plannedCheckOutDate", Comparator.comparing(FrontDeskStayRow::plannedCheckOutDate)
                    .thenComparing(FrontDeskStayRow::reservationNumber),
            "room", Comparator.comparing(
                            (FrontDeskStayRow row) -> row.rooms().isEmpty() ? "" : row.rooms().get(0).roomNumber())
                    .thenComparing(FrontDeskStayRow::reservationNumber));

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
     * Loads one page of Arrivals (Batch 3A read-model enhancement over {@link #arrivals()}). The business
     * population is exactly {@link #arrivals()}'s: this narrows it with an optional search fragment (matched
     * against Reservation number, Guest name/code, contact phone, OTA booking reference and Room number) and,
     * when a whitelisted column is requested, re-orders it by that column instead of the default
     * needs-attention/overdue-first ordering; otherwise the default ordering is kept exactly.
     *
     * @param criteria normalized optional search/sort state for this request
     * @param page zero-based requested page number
     * @return the matching page of arrival rows
     */
    @Transactional(readOnly = true)
    public Page<FrontDeskArrivalRow> arrivals(FrontDeskSearchCriteria criteria, int page) {
        List<FrontDeskArrivalRow> matching = filterArrivals(arrivals(), criteria.getSearch());
        return paginate(sortArrivals(matching, criteria.getSort(), criteria.getDir()), page);
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
     * Loads one page of Departures (Batch 3A read-model enhancement over {@link #departures(boolean)}). The
     * business population is exactly {@link #departures(boolean)}'s: this narrows it with an optional search
     * fragment (matched against Reservation number, Guest name/code and Room number) and, when a whitelisted
     * column is requested, re-orders it by that column instead of the default overdue/payment-required-first
     * ordering; otherwise the default ordering is kept exactly. Outstanding amount visibility is unaffected and
     * still follows {@code includeAmounts} exactly as {@link #departures(boolean)} already does.
     *
     * @param includeAmounts whether the outstanding amount may be included (user holds MANAGE_PAYMENT)
     * @param criteria normalized optional search/sort state for this request
     * @param page zero-based requested page number
     * @return the matching page of departure rows
     */
    @Transactional(readOnly = true)
    public Page<FrontDeskStayRow> departures(boolean includeAmounts, FrontDeskSearchCriteria criteria, int page) {
        List<FrontDeskStayRow> matching = filterStays(departures(includeAmounts), criteria.getSearch());
        return paginate(
                sortStays(matching, TableSorts.FRONT_DESK_DEPARTURES, criteria.getSort(), criteria.getDir()), page);
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

    /**
     * Loads one page of In-house (Batch 3A read-model enhancement over {@link #inHouse()}). The business
     * population is exactly {@link #inHouse()}'s: this narrows it with an optional search fragment (matched
     * against Reservation number, Guest name/code and Room number) and, when a whitelisted column is requested,
     * re-orders it by that column instead of the default room-number ordering; otherwise the default ordering is
     * kept exactly. No Charge/Payment query is added: {@link #inHouse()} still never reads money (§9.2.4).
     *
     * @param criteria normalized optional search/sort state for this request
     * @param page zero-based requested page number
     * @return the matching page of in-house rows
     */
    @Transactional(readOnly = true)
    public Page<FrontDeskStayRow> inHouse(FrontDeskSearchCriteria criteria, int page) {
        List<FrontDeskStayRow> matching = filterStays(inHouse(), criteria.getSearch());
        return paginate(
                sortStays(matching, TableSorts.FRONT_DESK_IN_HOUSE, criteria.getSort(), criteria.getDir()), page);
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

    /**
     * Narrows an already-loaded, already business-scoped Arrivals list to the rows matching one search fragment.
     * Matching reuses data every row already carries (no extra lookup): Reservation number, Guest name/code,
     * contact phone, OTA booking reference, and every booked Room number.
     *
     * @param rows the full Arrivals result for the hotel date, unchanged
     * @param search normalized optional search fragment, or {@code null} for no filtering
     * @return the matching rows, in their original relative order
     */
    private List<FrontDeskArrivalRow> filterArrivals(List<FrontDeskArrivalRow> rows, String search) {
        if (search == null) {
            return rows;
        }
        String needle = search.toLowerCase(Locale.ROOT);
        return rows.stream().filter(row -> matchesArrival(row, needle)).toList();
    }

    /**
     * Tells whether one Arrivals row matches a lowercased search fragment.
     *
     * @param row candidate arrival row
     * @param needle already-lowercased, non-blank search fragment
     * @return {@code true} when any searchable field contains the fragment
     */
    private boolean matchesArrival(FrontDeskArrivalRow row, String needle) {
        return containsIgnoreCase(row.reservationNumber(), needle)
                || containsIgnoreCase(row.guestName(), needle)
                || containsIgnoreCase(row.guestCode(), needle)
                || containsIgnoreCase(row.contactPhone(), needle)
                || containsIgnoreCase(row.otaBookingReference(), needle)
                || row.rooms().stream().anyMatch(room -> containsIgnoreCase(room.roomNumber(), needle));
    }

    /**
     * Re-orders a filtered Arrivals list by a whitelisted column when one is validly requested, otherwise leaves
     * the default needs-attention/overdue-first ordering untouched.
     *
     * @param rows the filtered Arrivals rows, already in default order
     * @param sort requested public sort key, validated against {@link TableSorts#FRONT_DESK_ARRIVALS}
     * @param dir requested sort direction, validated against the same whitelist
     * @return the rows in the requested order, or unchanged when no valid sort was requested
     */
    private List<FrontDeskArrivalRow> sortArrivals(List<FrontDeskArrivalRow> rows, String sort, String dir) {
        String key = TableSorts.FRONT_DESK_ARRIVALS.key(sort, dir);
        if (key == null) {
            return rows;
        }
        Comparator<FrontDeskArrivalRow> comparator = ARRIVAL_SORTS.get(key);
        boolean descending = "desc".equals(TableSorts.FRONT_DESK_ARRIVALS.activeDirection(sort, dir));
        List<FrontDeskArrivalRow> sorted = new ArrayList<>(rows);
        sorted.sort(descending ? comparator.reversed() : comparator);
        return sorted;
    }

    /**
     * Narrows an already-loaded, already business-scoped Departures/In-house list to the rows matching one search
     * fragment. Matching reuses data every row already carries (no extra lookup): Reservation number, Guest
     * name/code, and every current Room number.
     *
     * @param rows the full Departures or In-house result, unchanged
     * @param search normalized optional search fragment, or {@code null} for no filtering
     * @return the matching rows, in their original relative order
     */
    private List<FrontDeskStayRow> filterStays(List<FrontDeskStayRow> rows, String search) {
        if (search == null) {
            return rows;
        }
        String needle = search.toLowerCase(Locale.ROOT);
        return rows.stream().filter(row -> matchesStay(row, needle)).toList();
    }

    /**
     * Tells whether one Departures/In-house row matches a lowercased search fragment.
     *
     * @param row candidate stay row
     * @param needle already-lowercased, non-blank search fragment
     * @return {@code true} when any searchable field contains the fragment
     */
    private boolean matchesStay(FrontDeskStayRow row, String needle) {
        return containsIgnoreCase(row.reservationNumber(), needle)
                || containsIgnoreCase(row.guestName(), needle)
                || containsIgnoreCase(row.guestCode(), needle)
                || row.rooms().stream().anyMatch(room -> containsIgnoreCase(room.roomNumber(), needle));
    }

    /**
     * Re-orders a filtered Departures/In-house list by a whitelisted column when one is validly requested,
     * otherwise leaves its own default ordering (overdue/payment-required-first for Departures, room-number for
     * In-house) untouched.
     *
     * @param rows the filtered rows, already in the view's default order
     * @param whitelist the requesting view's own sort whitelist ({@link TableSorts#FRONT_DESK_DEPARTURES} or
     *     {@link TableSorts#FRONT_DESK_IN_HOUSE})
     * @param sort requested public sort key, validated against {@code whitelist}
     * @param dir requested sort direction, validated against the same whitelist
     * @return the rows in the requested order, or unchanged when no valid sort was requested
     */
    private List<FrontDeskStayRow> sortStays(
            List<FrontDeskStayRow> rows, SortWhitelist whitelist, String sort, String dir) {
        String key = whitelist.key(sort, dir);
        if (key == null) {
            return rows;
        }
        Comparator<FrontDeskStayRow> comparator = STAY_SORTS.get(key);
        boolean descending = "desc".equals(whitelist.activeDirection(sort, dir));
        List<FrontDeskStayRow> sorted = new ArrayList<>(rows);
        sorted.sort(descending ? comparator.reversed() : comparator);
        return sorted;
    }

    /**
     * Tells whether a value contains a search fragment, case-insensitively.
     *
     * @param value field value, possibly {@code null}
     * @param needle already-lowercased, non-blank search fragment
     * @return {@code true} when {@code value} is present and contains the fragment
     */
    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Slices an already filtered and sorted in-memory list into one {@link Page}, the same shape the other list
     * screens already return from a database query, so the shared {@code PaginationSupport}/{@code layout/table}
     * presentation mechanics apply unchanged.
     *
     * @param rows the full filtered and sorted result for the requested view
     * @param page zero-based requested page number; a negative value is treated as {@code 0}
     * @param <T> the row type
     * @return the requested page, empty when {@code page} is beyond the last page
     */
    private <T> Page<T> paginate(List<T> rows, int page) {
        int pageNumber = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageNumber, FRONT_DESK_PAGE_SIZE);
        int total = rows.size();
        int start = Math.min(pageNumber * FRONT_DESK_PAGE_SIZE, total);
        int end = Math.min(start + FRONT_DESK_PAGE_SIZE, total);
        return new PageImpl<>(rows.subList(start, end), pageable, total);
    }
}
