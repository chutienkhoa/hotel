package com.example.hotel.service.booking;

import com.example.hotel.common.SortWhitelist;
import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.BookingSource;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
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
        List<FrontDeskArrivalRow> matching = filterArrivalFacets(filterArrivals(arrivals(), criteria.getSearch()), criteria);
        return paginate(sortArrivals(matching, criteria.getSort(), criteria.getDir()), page);
    }

    /**
     * Narrows an Arrivals list by the optional date, readiness and source filters. Each filter reads a value the row
     * already carries: the date filter uses the same overdue timing as the Overdue Arrival badge, readiness uses the
     * derived {@link ArrivalReadinessState} shown in the Status column, and source uses the Reservation's
     * {@link BookingSource}. An absent or unrecognized value applies no filter.
     *
     * @param rows the search-filtered Arrivals rows, in default order
     * @param criteria normalized request state holding the optional filter values
     * @return the rows matching every supplied filter, in their original relative order
     */
    private List<FrontDeskArrivalRow> filterArrivalFacets(List<FrontDeskArrivalRow> rows, FrontDeskSearchCriteria criteria) {
        ArrivalDateFilter date = parse(ArrivalDateFilter.class, criteria.getArrivalDate());
        ArrivalReadinessState state = parse(ArrivalReadinessState.class, criteria.getReadiness());
        BookingSource source = parse(BookingSource.class, criteria.getSource());
        return rows.stream()
                .filter(row -> date == null || (date == ArrivalDateFilter.OVERDUE) == row.overdue())
                .filter(row -> state == null || row.readiness().state() == state)
                .filter(row -> source == null || row.source() == source)
                .toList();
    }

    /**
     * Returns the enum constant named by a request value.
     *
     * @param type enum type to parse
     * @param value raw request value, possibly {@code null}
     * @param <E> enum type
     * @return the matching constant, or {@code null} when the value is absent or not a constant of the type
     */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Arrivals date filter values: {@code TODAY} (not yet overdue) and {@code OVERDUE} (check-in date has passed). */
    private enum ArrivalDateFilter {
        TODAY,
        OVERDUE
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
        List<FrontDeskStayRow> matching = filterDepartureFacets(
                filterStays(departures(includeAmounts), criteria.getSearch()), criteria, hotelToday());
        return paginate(
                sortStays(matching, TableSorts.FRONT_DESK_DEPARTURES, criteria.getSort(), criteria.getDir(), includeAmounts),
                page);
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
        List<FrontDeskStayRow> matching = filterInHouseFacets(filterStays(inHouse(), criteria.getSearch()), criteria);
        return paginate(
                sortStays(matching, TableSorts.FRONT_DESK_IN_HOUSE, criteria.getSort(), criteria.getDir(), false), page);
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
                    new FrontDeskRoomResponse(room.getId(), room.getRoomNumber(), room.getRoomType().getId(),
                            room.getRoomType().getName(), null));
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
                    reservation.getCurrency(),
                    reservation.getSource(),
                    ChronoUnit.DAYS.between(reservation.getCheckInDate(), reservation.getCheckOutDate())));
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
                        room.getId(), room.getRoomNumber(), room.getRoomType().getId(), room.getRoomType().getName(),
                        blockerByRoom.get(room.getId())))
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
        boolean descending = "desc".equals(TableSorts.FRONT_DESK_ARRIVALS.activeDirection(sort, dir));
        return rows.stream()
                .sorted(arrivalColumn(key, descending).thenComparing(FrontDeskArrivalRow::reservationNumber))
                .toList();
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
     * Narrows In-house rows by the optional Room Type and Source filters. Room Type matches the CURRENT Room of the
     * Stay (its open StayRoomAssignment), so a guest who has been moved is filtered by the room they occupy now.
     * An absent or unrecognized value applies no filter.
     *
     * @param rows the search-filtered In-house rows, in default order
     * @param criteria normalized request state holding the optional filter values
     * @return the rows matching every supplied filter, in their original relative order
     */
    private List<FrontDeskStayRow> filterInHouseFacets(List<FrontDeskStayRow> rows, FrontDeskSearchCriteria criteria) {
        UUID roomTypeId = parseUuid(criteria.getRoomType());
        BookingSource source = parse(BookingSource.class, criteria.getSource());
        return rows.stream()
                .filter(row -> roomTypeId == null
                        || row.rooms().stream().anyMatch(room -> roomTypeId.equals(room.roomTypeId())))
                .filter(row -> source == null || row.source() == source)
                .toList();
    }

    /**
     * Narrows Departures rows by the optional checkout-date, status and Source filters. Every value is read from the
     * row, which already carries the current planned checkout (Reservation.checkOutDate, so an extended stay uses its
     * new date), the overdue flag and the payment-required flag computed by the existing readiness rules. Nothing is
     * recomputed here, and no new Reservation status is introduced.
     *
     * @param rows the search-filtered Departures rows, in default order
     * @param criteria normalized request state holding the optional filter values
     * @param today the hotel date
     * @return the rows matching every supplied filter, in their original relative order
     */
    private List<FrontDeskStayRow> filterDepartureFacets(
            List<FrontDeskStayRow> rows, FrontDeskSearchCriteria criteria, LocalDate today) {
        DepartureCheckoutFilter date = parse(DepartureCheckoutFilter.class, criteria.getCheckoutDate());
        DepartureStatusFilter status = parse(DepartureStatusFilter.class, criteria.getStatus());
        BookingSource source = parse(BookingSource.class, criteria.getSource());
        return rows.stream()
                .filter(row -> date == null || switch (date) {
                    case TODAY -> row.plannedCheckOutDate().isEqual(today);
                    case OVERDUE -> row.plannedCheckOutDate().isBefore(today);
                })
                .filter(row -> status == null || switch (status) {
                    case OVERDUE -> row.overdue();
                    case PAYMENT_REQUIRED -> row.paymentRequired();
                    case READY -> !row.overdue() && !row.paymentRequired();
                })
                .filter(row -> source == null || row.source() == source)
                .toList();
    }

    /**
     * Parses a request value as a RoomType identifier.
     *
     * @param value raw request value, possibly {@code null}
     * @return the identifier, or {@code null} when absent or malformed
     */
    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Departures checkout-date filter values, relative to the hotel date: {@code TODAY} or {@code OVERDUE}. */
    private enum DepartureCheckoutFilter {
        TODAY,
        OVERDUE
    }

    /** Departures status filter values, from the existing readiness facts: ready, overdue or payment required. */
    private enum DepartureStatusFilter {
        READY,
        OVERDUE,
        PAYMENT_REQUIRED
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
            List<FrontDeskStayRow> rows, SortWhitelist whitelist, String sort, String dir, boolean includeAmounts) {
        String key = whitelist.key(sort, dir);
        // Outstanding is a money column: it is sortable only for users who may see it, otherwise the default order applies.
        if (key == null || ("outstanding".equals(key) && !includeAmounts)) {
            return rows;
        }
        boolean descending = "desc".equals(whitelist.activeDirection(sort, dir));
        return rows.stream()
                .sorted(stayColumn(key, descending).thenComparing(FrontDeskStayRow::reservationNumber))
                .toList();
    }

    /**
     * Maps one Arrivals sort key to its column value. Every column sorts by the value the screen shows, or by the
     * underlying value where the displayed text would sort wrongly (dates, not formatted strings). Status uses the
     * same rank as the default Arrivals order, so it is the derived readiness/overdue state and no stored status.
     *
     * @param key a key already validated against {@link TableSorts#FRONT_DESK_ARRIVALS}
     * @param descending whether the column sorts descending
     * @return the comparator for that column, with nulls last in both directions
     */
    private static Comparator<FrontDeskArrivalRow> arrivalColumn(String key, boolean descending) {
        return switch (key) {
            case "reservationNumber" -> column(FrontDeskArrivalRow::reservationNumber, Comparator.naturalOrder(), descending);
            case "guestName" -> column(FrontDeskArrivalRow::guestName, String.CASE_INSENSITIVE_ORDER, descending);
            case "checkInDate" -> column(FrontDeskArrivalRow::checkInDate, Comparator.naturalOrder(), descending);
            case "room" -> column(FrontDeskQueryService::arrivalRoomNumber, Comparator.naturalOrder(), descending);
            case "source" -> column(FrontDeskQueryService::arrivalSourceLabel, Comparator.naturalOrder(), descending);
            case "status" -> column(FrontDeskQueryService::arrivalRank, Comparator.naturalOrder(), descending);
            default -> throw new IllegalArgumentException("Unsupported Arrivals sort key: " + key);
        };
    }

    private static String arrivalRoomNumber(FrontDeskArrivalRow row) {
        return primaryRoomNumber(row.rooms());
    }

    private static String arrivalSourceLabel(FrontDeskArrivalRow row) {
        return sourceLabel(row.source());
    }

    private static String stayRoomNumber(FrontDeskStayRow row) {
        return primaryRoomNumber(row.rooms());
    }

    private static String staySourceLabel(FrontDeskStayRow row) {
        return sourceLabel(row.source());
    }

    /**
     * Maps one Departures/In-house sort key to its column value; see {@link #arrivalColumn(String, boolean)}. Room uses
     * the CURRENT rooms of the row (open StayRoomAssignments, never the originally booked lines), Nights and
     * Outstanding compare numerically, Status uses the same rank as the default Departures order.
     *
     * @param key a key already validated against the view's whitelist
     * @param descending whether the column sorts descending
     * @return the comparator for that column, with nulls last in both directions
     */
    private static Comparator<FrontDeskStayRow> stayColumn(String key, boolean descending) {
        return switch (key) {
            case "reservationNumber" -> column(FrontDeskStayRow::reservationNumber, Comparator.naturalOrder(), descending);
            case "guestName" -> column(FrontDeskStayRow::guestName, String.CASE_INSENSITIVE_ORDER, descending);
            case "room" -> column(FrontDeskQueryService::stayRoomNumber, Comparator.naturalOrder(), descending);
            case "plannedCheckOutDate" -> column(FrontDeskStayRow::plannedCheckOutDate, Comparator.naturalOrder(), descending);
            case "checkedIn" -> column(FrontDeskStayRow::actualCheckInAt, Comparator.naturalOrder(), descending);
            case "nights" -> column(FrontDeskStayRow::nights, Comparator.naturalOrder(), descending);
            case "source" -> column(FrontDeskQueryService::staySourceLabel, Comparator.naturalOrder(), descending);
            case "status" -> column(FrontDeskQueryService::departureRank, Comparator.naturalOrder(), descending);
            case "outstanding" -> column(FrontDeskStayRow::outstanding, Comparator.naturalOrder(), descending);
            default -> throw new IllegalArgumentException("Unsupported Front Desk sort key: " + key);
        };
    }

    /**
     * Builds one column comparator. Nulls sort last whether the column is ascending or descending, so a missing value
     * never jumps to the top when the direction flips.
     *
     * @param key extracts the column value from a row
     * @param order the value ordering (natural or case-insensitive)
     * @param descending whether the column sorts descending
     * @param <T> row type
     * @param <K> column value type
     * @return the null-safe comparator for the column
     */
    private static <T, K> Comparator<T> column(Function<T, K> key, Comparator<K> order, boolean descending) {
        Comparator<K> ordered = descending ? order.reversed() : order;
        return Comparator.comparing(key, Comparator.nullsLast(ordered));
    }

    /**
     * Returns the Room number used for the Room column: the lowest current Room number of the row, so a multi-room row
     * sorts the same way regardless of the order its rooms were loaded in.
     *
     * @param rooms the row's current Rooms
     * @return the lowest room number, or {@code null} when the row has no Room
     */
    private static String primaryRoomNumber(List<FrontDeskRoomResponse> rooms) {
        return rooms.stream().map(FrontDeskRoomResponse::roomNumber).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Returns the staff-facing Source label, sorted alphabetically as the screen shows it.
     *
     * @param source Reservation source, possibly {@code null}
     * @return the display label, or {@code null} when absent
     */
    private static String sourceLabel(BookingSource source) {
        return source == null ? null : source.getDisplayName();
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
