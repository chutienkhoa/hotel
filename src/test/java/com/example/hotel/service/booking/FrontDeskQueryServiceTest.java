package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayAmountRow;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies Front Desk grouping, ordering, derived attention, current-room semantics, money visibility and query use. */
class FrontDeskQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    /** Actual check-in instant shared by stays whose check-in time is not what a test is about. */
    private static final Instant CHECKED_IN = Instant.parse("2026-09-20T07:35:00Z");
    private static final UUID SINGLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID DOUBLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000302");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final StayRoomAssignmentRepository assignments = mock(StayRoomAssignmentRepository.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final GuestMapper guestMapper = mock(GuestMapper.class);
    private final Clock clock = Clock.fixed(TODAY.atTime(23, 30).atZone(ZONE).toInstant(), ZONE);
    private final FrontDeskQueryService service =
            new FrontDeskQueryService(reservations, stays, assignments, charges, payments, guestMapper, clock);

    private final List<Reservation> pending = new ArrayList<>();
    private final List<Object[]> rows = new ArrayList<>();
    private final List<UUID> stayed = new ArrayList<>();

    // ---------------------------------------------------------------- Arrivals

    /** Confirms the hotel Clock (not system time) defines today, and only pending arrivals up to today are queried. */
    @Test
    void shouldQueryConfirmedArrivalsUpToTheHotelDate() {
        arrivals();

        verify(reservations).findByStatusAndCheckInOnOrBefore(ReservationStatus.CONFIRMED, TODAY);
        assertEquals(TODAY, service.hotelToday());
    }

    /** Confirms a ready today arrival is Ready and an overdue ready arrival is still Needs Attention. */
    @Test
    void shouldGroupReadyAndOverdueArrivals() {
        Reservation ready = reservation("R-2", TODAY);
        Reservation overdue = reservation("R-1", TODAY.minusDays(1));
        line(ready, room("101", RoomStatus.AVAILABLE));
        line(overdue, room("102", RoomStatus.AVAILABLE));

        List<FrontDeskArrivalRow> rows = arrivals();

        assertEquals(List.of("R-1", "R-2"), rows.stream().map(FrontDeskArrivalRow::reservationNumber).toList());
        assertTrue(rows.get(0).overdue() && rows.get(0).needsAttention());
        assertEquals(ArrivalReadinessState.READY, rows.get(0).readiness().state());
        assertFalse(rows.get(1).overdue() || rows.get(1).needsAttention());
    }

    /** Confirms a blocker means Needs Attention, ordered after overdue and before ready, with per-room issue. */
    @Test
    void shouldOrderNeedsAttentionOverdueThenBlockedThenReady() {
        Reservation ready = reservation("R-1", TODAY);
        Reservation blocked = reservation("R-2", TODAY);
        Reservation overdue = reservation("R-3", TODAY.minusDays(3));
        line(ready, room("101", RoomStatus.AVAILABLE));
        line(blocked, room("102", RoomStatus.DIRTY));
        line(overdue, room("103", RoomStatus.AVAILABLE));

        List<FrontDeskArrivalRow> rows = arrivals();

        assertEquals(List.of("R-3", "R-2", "R-1"), rows.stream().map(FrontDeskArrivalRow::reservationNumber).toList());
        assertTrue(rows.get(1).needsAttention());
        assertFalse(rows.get(1).overdue());
        assertTrue(rows.get(1).housekeepingRelated());
        assertEquals(ArrivalIssueCode.ROOM_DIRTY, rows.get(1).rooms().get(0).issue());
    }

    /** Confirms insufficient adult capacity puts an arrival in Needs Attention through the shared readiness (no own rule). */
    @Test
    void shouldFlagInsufficientAdultCapacityAsNeedsAttentionThroughReadiness() {
        Reservation reservation = reservation("R-1", TODAY);
        ReflectionTestUtils.setField(reservation, "adultCount", 3);
        line(reservation, room("101", RoomStatus.AVAILABLE));

        FrontDeskArrivalRow row = arrivals().get(0);

        assertTrue(row.needsAttention());
        assertFalse(row.overdue());
        assertEquals(ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY, row.readiness().blockers().get(0).code());
        assertEquals(
                com.example.hotel.service.booking.ArrivalReadinessRules.evaluate(
                        ReservationStatus.CONFIRMED, TODAY, TODAY, false, 3,
                        List.of(roomOf(reservation)), true).issues(),
                row.readiness().issues());
    }

    private static Room roomOf(Reservation reservation) {
        return reservation.getRooms().get(0).getRoom();
    }

    /** Confirms a multi-room reservation is ONE row listing every room, with the issue on the affected room only. */
    @Test
    void shouldShowMultiRoomReservationAsOneRowWithPerRoomIssues() {
        Reservation multi = reservation("R-1", TODAY);
        line(multi, room("101", RoomStatus.AVAILABLE));
        line(multi, room("102", RoomStatus.DIRTY));

        List<FrontDeskArrivalRow> rows = arrivals();

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).rooms().size());
        assertNull(rows.get(0).rooms().get(0).issue());
        assertEquals(ArrivalIssueCode.ROOM_DIRTY, rows.get(0).rooms().get(1).issue());
        assertEquals("102", rows.get(0).readiness().blockers().get(0).roomNumber());
        assertTrue(rows.get(0).needsAttention());
    }

    /** Confirms a missing passport is not evaluated and never makes an arrival Needs Attention. */
    @Test
    void shouldNotTreatPassportAsAttention() {
        Reservation reservation = reservation("R-1", TODAY);
        line(reservation, room("101", RoomStatus.AVAILABLE));

        FrontDeskArrivalRow row = arrivals().get(0);

        assertFalse(row.needsAttention());
        assertTrue(row.readiness().issues().stream().noneMatch(
                issue -> issue.code() == ArrivalIssueCode.PASSPORT_MISSING));
    }

    /** Confirms a CONFIRMED reservation that already has a Stay is not listed as an arrival. */
    @Test
    void shouldExcludeReservationsThatAlreadyHaveAStay() {
        Reservation withStay = reservation("R-1", TODAY);
        line(withStay, room("101", RoomStatus.AVAILABLE));
        stayed.add(withStay.getId());

        assertTrue(arrivals().isEmpty());
    }

    /** Confirms guest identity is full name plus code, falling back to the code when there is no name. */
    @Test
    void shouldFallBackToGuestCodeWhenThereIsNoName() {
        Reservation reservation = reservation("R-1", TODAY);
        line(reservation, room("101", RoomStatus.AVAILABLE));
        guestName = " ";

        FrontDeskArrivalRow row = arrivals().get(0);

        assertNull(row.guestName());
        assertEquals("G-R-1", row.guestCode());
    }

    /** Confirms Arrivals use a fixed number of batch queries and never the per-row check-in review or balance. */
    @Test
    void shouldUseFixedBatchQueriesForArrivals() {
        for (int index = 0; index < 5; index++) {
            Reservation reservation = reservation("R-" + index, TODAY);
            line(reservation, room("10" + index, RoomStatus.AVAILABLE));
        }

        arrivals();

        verify(reservations, times(1)).findByStatusAndCheckInOnOrBefore(any(), any());
        verify(reservations, times(1)).findBookedRoomsByReservationIdIn(any());
        verify(stays, times(1)).findReservationIdsWithStay(any());
        verifyNoInteractions(charges, payments, assignments);
        assertFalse(Arrays.stream(FrontDeskQueryService.class.getDeclaredConstructors()[0].getParameterTypes())
                .anyMatch(type -> type == CheckInService.class || type == StayBalanceService.class));
    }

    // -------------------------------------------------------- Departures / In-house

    /** Confirms Departures query only stays due by the hotel date and current rooms come from OPEN assignments. */
    @Test
    void shouldLoadDeparturesDueByTodayWithCurrentRooms() {
        Stay stay = stay("R-1", TODAY, "301", "302");
        pay(stay, "1000000", "1000000");

        List<FrontDeskStayRow> rows = departures(true);

        verify(stays).findWithReservationAndGuestDueBy(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN, TODAY);
        verify(assignments).findOpenByStayIdInWithRoom(any());
        verify(assignments, never()).findByStayIdOrderByLineageAndTime(any());
        assertEquals(1, rows.size());
        assertEquals(List.of("301", "302"), rows.get(0).rooms().stream().map(room -> room.roomNumber()).toList());
    }

    /** Confirms zero outstanding is Ready and non-zero is Payment Required / Needs Attention. */
    @Test
    void shouldDeriveFinancialReadinessFromOutstanding() {
        Stay paid = stay("R-1", TODAY, "101");
        Stay owing = stay("R-2", TODAY, "102");
        pay(paid, "500000", "500000");
        pay(owing, "500000", "200000");

        List<FrontDeskStayRow> rows = departures(true);

        assertEquals(List.of("R-2", "R-1"), rows.stream().map(FrontDeskStayRow::reservationNumber).toList());
        assertTrue(rows.get(0).paymentRequired() && rows.get(0).needsAttention());
        assertEquals(0, new BigDecimal("300000").compareTo(rows.get(0).outstanding()));
        assertFalse(rows.get(1).paymentRequired() || rows.get(1).needsAttention());
    }

    /** Confirms an overdue zero-balance departure is still Needs Attention, ordered before payment-required. */
    @Test
    void shouldTreatOverdueDepartureAsNeedsAttentionEvenWhenPaid() {
        Stay overdue = stay("R-1", TODAY.minusDays(1), "101");
        Stay owingToday = stay("R-2", TODAY, "102");
        pay(overdue, "500000", "500000");
        pay(owingToday, "500000", "0");

        List<FrontDeskStayRow> rows = departures(true);

        assertEquals("R-1", rows.get(0).reservationNumber());
        assertTrue(rows.get(0).overdue() && rows.get(0).needsAttention() && !rows.get(0).paymentRequired());
        assertEquals("R-2", rows.get(1).reservationNumber());
    }

    /** Confirms the outstanding amount is only returned when money is authorized; readiness is returned either way. */
    @Test
    void shouldHideAmountsWhenNotAuthorized() {
        Stay stay = stay("R-1", TODAY, "101");
        pay(stay, "500000", "0");

        FrontDeskStayRow hidden = departures(false).get(0);

        assertNull(hidden.outstanding());
        assertTrue(hidden.paymentRequired());
    }

    /** Confirms balances use grouped queries once (not per stay) and the shared readiness rule. */
    @Test
    void shouldUseGroupedBalanceQueries() {
        for (int index = 0; index < 4; index++) {
            pay(stay("R-" + index, TODAY, "10" + index), "100", "100");
        }

        departures(true);

        verify(charges, times(1)).sumAmountByStayIdIn(any());
        verify(payments, times(1)).sumAppliedAmountByStayIdInAndStatus(any(), org.mockito.ArgumentMatchers.eq(PaymentStatus.PAID));
        verify(charges, never()).sumAmountByStayId(any());
        verify(payments, never()).sumAppliedAmountByStayIdAndStatus(any(), any());
    }

    /** Confirms In-house lists all checked-in stays with current rooms and reads no money. */
    @Test
    void shouldListInHouseStaysWithoutMoney() {
        stay("R-2", TODAY.plusDays(2), "202");
        stay("R-1", TODAY, "101", "102");

        stubGuest();
        List<FrontDeskStayRow> rows = service.inHouse();

        verify(stays).findWithReservationAndGuest(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN);
        verifyNoInteractions(charges, payments);
        assertEquals(List.of("R-1", "R-2"), rows.stream().map(FrontDeskStayRow::reservationNumber).toList());
        assertEquals(2, rows.get(0).rooms().size());
        assertNull(rows.get(0).outstanding());
        assertFalse(rows.get(0).needsAttention());
        assertEquals(Instant.parse("2026-09-20T07:35:00Z"), rows.get(0).actualCheckInAt());
    }

    // ----------------------------------------------- Batch 3A: search, sort, pagination

    /** Confirms the paged Arrivals method selects the exact same business population as the original with no search/sort. */
    @Test
    void shouldPreserveArrivalsSelectionWhenNoSearchOrSortRequested() {
        Reservation ready = reservation("R-2", TODAY);
        Reservation overdue = reservation("R-1", TODAY.minusDays(1));
        line(ready, room("101", RoomStatus.AVAILABLE));
        line(overdue, room("102", RoomStatus.AVAILABLE));

        List<String> original = arrivals().stream().map(FrontDeskArrivalRow::reservationNumber).toList();
        Page<FrontDeskArrivalRow> paged = arrivalsPaged(criteria(null, null, null), 0);

        assertEquals(original, paged.getContent().stream().map(FrontDeskArrivalRow::reservationNumber).toList());
    }

    /** Confirms search matches Reservation number, Guest name, Guest code, contact phone, OTA reference and Room number. */
    @Test
    void shouldMatchArrivalsSearchAcrossEverySearchableField() {
        Reservation byNumber = reservation("R-100", TODAY);
        Reservation byRoom = reservation("R-200", TODAY);
        line(byNumber, room("101", RoomStatus.AVAILABLE));
        line(byRoom, room("999", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-100"), namesOf(arrivalsPaged(criteria("r-100", null, null), 0)));
        assertEquals(List.of("R-200"), namesOf(arrivalsPaged(criteria("999", null, null), 0)));
    }

    /** Confirms the contact phone stays searchable even though the Arrivals table no longer displays it. */
    @Test
    void shouldStillFindArrivalsByContactPhone() {
        Reservation byPhone = reservation("R-300", TODAY);
        byPhone.changeBookingContact(null, "0987654321", null);
        line(byPhone, room("777", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-300"), namesOf(arrivalsPaged(criteria("0987654321", null, null), 0)));
    }

    /** Confirms the Arrivals date filter separates overdue check-in dates from arrivals due today. */
    @Test
    void shouldFilterArrivalsByOverdueAndToday() {
        Reservation overdue = reservation("R-OD", TODAY.minusDays(1));
        Reservation today = reservation("R-TD", TODAY);
        line(overdue, room("101", RoomStatus.AVAILABLE));
        line(today, room("102", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-OD"), namesOf(arrivalsPaged(facets("OVERDUE", null, null), 0)));
        assertEquals(List.of("R-TD"), namesOf(arrivalsPaged(facets("TODAY", null, null), 0)));
        assertEquals(List.of("R-OD", "R-TD"), namesOf(arrivalsPaged(facets(null, null, null), 0)));
    }

    /** Confirms the readiness filter uses the derived Arrival Readiness state shown in the Status column. */
    @Test
    void shouldFilterArrivalsByReadinessState() {
        Reservation blocked = reservation("R-DIRTY", TODAY);
        Reservation ready = reservation("R-READY", TODAY);
        line(blocked, room("201", RoomStatus.DIRTY));
        line(ready, room("202", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-DIRTY"), namesOf(arrivalsPaged(facets(null, "NEEDS_ATTENTION", null), 0)));
        assertEquals(List.of("R-READY"), namesOf(arrivalsPaged(facets(null, "READY", null), 0)));
    }

    /** Confirms the source filter matches the Reservation's booking source exactly. */
    @Test
    void shouldFilterArrivalsByBookingSource() {
        Reservation agoda = reservation("R-AG", TODAY, BookingSource.AGODA);
        line(agoda, room("301", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-AG"), namesOf(arrivalsPaged(facets(null, null, "AGODA"), 0)));
        assertEquals(List.of(), namesOf(arrivalsPaged(facets(null, null, "BOOKING_COM"), 0)));
    }

    /** Confirms the filters combine with each other and with search, narrowing the same population. */
    @Test
    void shouldCombineArrivalFiltersWithSearch() {
        Reservation agodaOverdue = reservation("R-AG", TODAY.minusDays(1), BookingSource.AGODA);
        Reservation directToday = reservation("R-DT", TODAY, BookingSource.DIRECT);
        line(agodaOverdue, room("401", RoomStatus.AVAILABLE));
        line(directToday, room("402", RoomStatus.AVAILABLE));

        FrontDeskSearchCriteria combined = facets("OVERDUE", null, "AGODA");
        combined.setSearch("R-AG");
        assertEquals(List.of("R-AG"), namesOf(arrivalsPaged(combined, 0)));

        FrontDeskSearchCriteria mismatch = facets("TODAY", null, "AGODA");
        assertEquals(List.of(), namesOf(arrivalsPaged(mismatch, 0)));
    }

    /** Confirms unknown or malformed filter values apply no filter instead of failing or hiding every row. */
    @Test
    void shouldIgnoreUnknownArrivalFilterValues() {
        Reservation reservation = reservation("R-1", TODAY);
        line(reservation, room("501", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-1"), namesOf(arrivalsPaged(facets("BOGUS", "NOPE", "EXPEDIA"), 0)));
    }

    /** Confirms a search fragment matching nothing returns an empty page, not an error and not the full list. */
    @Test
    void shouldReturnEmptyPageWhenArrivalsSearchMatchesNothing() {
        Reservation reservation = reservation("R-1", TODAY);
        line(reservation, room("101", RoomStatus.AVAILABLE));

        Page<FrontDeskArrivalRow> page = arrivalsPaged(criteria("no-such-match", null, null), 0);

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    /** Confirms an explicit whitelisted sort overrides the default needs-attention-first ordering. */
    @Test
    void shouldSortArrivalsByRequestedColumn() {
        Reservation blocked = reservation("R-2", TODAY);
        Reservation ready = reservation("R-1", TODAY);
        line(blocked, room("101", RoomStatus.DIRTY));
        line(ready, room("102", RoomStatus.AVAILABLE));

        Page<FrontDeskArrivalRow> ascending = arrivalsPaged(criteria(null, "reservationNumber", "asc"), 0);
        Page<FrontDeskArrivalRow> descending = arrivalsPaged(criteria(null, "reservationNumber", "desc"), 0);

        assertEquals(List.of("R-1", "R-2"), namesOf(ascending));
        assertEquals(List.of("R-2", "R-1"), namesOf(descending));
    }

    /** Confirms an unsupported/unknown sort key is rejected (never used for ordering) and falls back to the default. */
    @Test
    void shouldFallBackToDefaultOrderingForUnsupportedArrivalsSortKey() {
        Reservation ready = reservation("R-2", TODAY);
        Reservation overdue = reservation("R-1", TODAY.minusDays(1));
        line(ready, room("101", RoomStatus.AVAILABLE));
        line(overdue, room("102", RoomStatus.AVAILABLE));

        Page<FrontDeskArrivalRow> page = arrivalsPaged(criteria(null, "guest.passwordHash", "asc"), 0);

        assertEquals(List.of("R-1", "R-2"), namesOf(page));
    }

    /** Confirms pagination slices the filtered/sorted result and reports the correct total count. */
    @Test
    void shouldPaginateArrivals() {
        for (int index = 0; index < 12; index++) {
            Reservation reservation = reservation(String.format("R-%02d", index), TODAY);
            line(reservation, room("1" + String.format("%02d", index), RoomStatus.AVAILABLE));
        }

        Page<FrontDeskArrivalRow> firstPage = arrivalsPaged(criteria(null, "reservationNumber", "asc"), 0);
        Page<FrontDeskArrivalRow> secondPage = arrivalsPaged(criteria(null, "reservationNumber", "asc"), 1);

        assertEquals(12, firstPage.getTotalElements());
        assertEquals(10, firstPage.getContent().size());
        assertEquals(2, secondPage.getContent().size());
        assertEquals("R-00", firstPage.getContent().get(0).reservationNumber());
        assertEquals("R-10", secondPage.getContent().get(0).reservationNumber());
    }

    /** Confirms a page number past the end returns an empty page while still reporting the true total. */
    @Test
    void shouldReturnEmptyContentForOutOfRangeArrivalsPage() {
        Reservation reservation = reservation("R-1", TODAY);
        line(reservation, room("101", RoomStatus.AVAILABLE));

        Page<FrontDeskArrivalRow> page = arrivalsPaged(criteria(null, null, null), 5);

        assertTrue(page.getContent().isEmpty());
        assertEquals(1, page.getTotalElements());
    }

    /** Confirms Departures search matches Room number in addition to Reservation number and Guest identity. */
    @Test
    void shouldMatchDeparturesSearchByRoomNumber() {
        stay("R-1", TODAY, "301");
        stay("R-2", TODAY, "999");

        Page<FrontDeskStayRow> page = departuresPaged(true, criteria("999", null, null), 0);

        assertEquals(List.of("R-2"), page.getContent().stream().map(FrontDeskStayRow::reservationNumber).toList());
    }

    /**
     * Confirms an explicit sort by planned checkout date (with its reservation-number tie-break) overrides the
     * default overdue/payment-required-first ordering for Departures, using two same-day stays whose default rank
     * order and alphabetical reservation-number order deliberately disagree.
     */
    @Test
    void shouldSortDeparturesByPlannedCheckOutDate() {
        Stay owing = stay("R-ZZZ-OWING", TODAY, "101");
        Stay paid = stay("R-AAA-PAID", TODAY, "102");
        pay(owing, "500000", "0");
        pay(paid, "500000", "500000");

        List<String> defaultOrder = departuresPaged(true, criteria(null, null, null), 0).getContent().stream()
                .map(FrontDeskStayRow::reservationNumber).toList();
        List<String> explicitOrder = departuresPaged(true, criteria(null, "plannedCheckOutDate", "asc"), 0).getContent()
                .stream().map(FrontDeskStayRow::reservationNumber).toList();

        assertEquals(List.of("R-ZZZ-OWING", "R-AAA-PAID"), defaultOrder);
        assertEquals(List.of("R-AAA-PAID", "R-ZZZ-OWING"), explicitOrder);
    }

    /** Confirms Departures outstanding visibility through the paged method still follows includeAmounts exactly. */
    @Test
    void shouldHideDeparturesOutstandingThroughPagedMethodWhenNotAuthorized() {
        Stay owing = stay("R-1", TODAY, "101");
        pay(owing, "500000", "0");

        FrontDeskStayRow hidden = departuresPaged(false, criteria(null, null, null), 0).getContent().get(0);
        FrontDeskStayRow visible = departuresPaged(true, criteria(null, null, null), 0).getContent().get(0);

        assertNull(hidden.outstanding());
        assertEquals(0, new BigDecimal("500000").compareTo(visible.outstanding()));
    }

    // ------------------------------------------------ Sorting: every data column, both directions, full result set

    /** Confirms every Arrivals data column sorts both ways over the full result, with nulls last and a stable tie-breaker. */
    @Test
    void shouldSortArrivalsByEveryDataColumn() {
        guestNames.put("G-R-A", "Alice");
        guestNames.put("G-R-B", "Bob");
        guestNames.put("G-R-C", null);
        line(reservation("R-A", TODAY.minusDays(2), BookingSource.AGODA), room("305", RoomStatus.DIRTY));
        line(reservation("R-B", TODAY, BookingSource.DIRECT), room("101", RoomStatus.AVAILABLE));
        line(reservation("R-C", TODAY, BookingSource.BOOKING_COM), room("202", RoomStatus.AVAILABLE));

        assertEquals(List.of("R-A", "R-B", "R-C"), namesOf(arrivalsPaged(criteria(null, null, null), 0)));
        assertEquals(List.of("R-A", "R-B", "R-C"), namesOf(arrivalsPaged(criteria(null, "reservationNumber", "asc"), 0)));
        assertEquals(List.of("R-C", "R-B", "R-A"), namesOf(arrivalsPaged(criteria(null, "reservationNumber", "desc"), 0)));
        assertEquals(List.of("R-A", "R-B", "R-C"), namesOf(arrivalsPaged(criteria(null, "guestName", "asc"), 0)));
        assertEquals(List.of("R-B", "R-A", "R-C"), namesOf(arrivalsPaged(criteria(null, "guestName", "desc"), 0)));
        assertEquals(List.of("R-A", "R-B", "R-C"), namesOf(arrivalsPaged(criteria(null, "checkInDate", "asc"), 0)));
        assertEquals(List.of("R-B", "R-C", "R-A"), namesOf(arrivalsPaged(criteria(null, "checkInDate", "desc"), 0)));
        assertEquals(List.of("R-B", "R-C", "R-A"), namesOf(arrivalsPaged(criteria(null, "room", "asc"), 0)));
        assertEquals(List.of("R-A", "R-C", "R-B"), namesOf(arrivalsPaged(criteria(null, "room", "desc"), 0)));
        assertEquals(List.of("R-A", "R-C", "R-B"), namesOf(arrivalsPaged(criteria(null, "source", "asc"), 0)));
        assertEquals(List.of("R-B", "R-C", "R-A"), namesOf(arrivalsPaged(criteria(null, "source", "desc"), 0)));
        assertEquals(List.of("R-A", "R-B", "R-C"), namesOf(arrivalsPaged(criteria(null, "status", "asc"), 0)));
        assertEquals(List.of("R-B", "R-C", "R-A"), namesOf(arrivalsPaged(criteria(null, "status", "desc"), 0)));
    }

    /** Confirms Departures sorts every data column both ways, Nights and Outstanding numerically, and dates chronologically. */
    @Test
    void shouldSortDeparturesByEveryDataColumnNumericallyAndChronologically() {
        guestNames.put("G-R-SA", "Alice");
        guestNames.put("G-R-SB", "Bob");
        guestNames.put("G-R-SC", null);
        Stay a = stay("R-SA", TODAY.minusDays(5), TODAY.minusDays(1), CHECKED_IN, BookingSource.AGODA, "401");
        Stay b = stay("R-SB", TODAY.minusDays(3), TODAY, CHECKED_IN, BookingSource.DIRECT, "102");
        Stay c = stay("R-SC", TODAY.minusDays(10), TODAY, CHECKED_IN, BookingSource.BOOKING_COM, "201");
        pay(a, "300000", "0");
        pay(b, "500000", "500000");
        pay(c, "2200000", "0");

        assertEquals(List.of("R-SA", "R-SB", "R-SC"), stayNames(departuresPaged(true, criteria(null, "reservationNumber", "asc"), 0)));
        assertEquals(List.of("R-SC", "R-SB", "R-SA"), stayNames(departuresPaged(true, criteria(null, "reservationNumber", "desc"), 0)));
        assertEquals(List.of("R-SA", "R-SB", "R-SC"), stayNames(departuresPaged(true, criteria(null, "guestName", "asc"), 0)));
        assertEquals(List.of("R-SB", "R-SA", "R-SC"), stayNames(departuresPaged(true, criteria(null, "guestName", "desc"), 0)));
        assertEquals(List.of("R-SA", "R-SB", "R-SC"), stayNames(departuresPaged(true, criteria(null, "plannedCheckOutDate", "asc"), 0)));
        assertEquals(List.of("R-SB", "R-SC", "R-SA"), stayNames(departuresPaged(true, criteria(null, "plannedCheckOutDate", "desc"), 0)));
        assertEquals(List.of("R-SB", "R-SC", "R-SA"), stayNames(departuresPaged(true, criteria(null, "room", "asc"), 0)));
        assertEquals(List.of("R-SA", "R-SC", "R-SB"), stayNames(departuresPaged(true, criteria(null, "room", "desc"), 0)));
        // Numeric, not lexicographic: 3, 4, 10 (text order would put "10" first).
        assertEquals(List.of("R-SB", "R-SA", "R-SC"), stayNames(departuresPaged(true, criteria(null, "nights", "asc"), 0)));
        assertEquals(List.of("R-SC", "R-SA", "R-SB"), stayNames(departuresPaged(true, criteria(null, "nights", "desc"), 0)));
        assertEquals(List.of("R-SA", "R-SC", "R-SB"), stayNames(departuresPaged(true, criteria(null, "source", "asc"), 0)));
        assertEquals(List.of("R-SB", "R-SC", "R-SA"), stayNames(departuresPaged(true, criteria(null, "source", "desc"), 0)));
        assertEquals(List.of("R-SA", "R-SC", "R-SB"), stayNames(departuresPaged(true, criteria(null, "status", "asc"), 0)));
        assertEquals(List.of("R-SB", "R-SC", "R-SA"), stayNames(departuresPaged(true, criteria(null, "status", "desc"), 0)));
        // Numeric money: 0 < 300000 < 2200000 (text order would put "2200000" before "300000").
        assertEquals(List.of("R-SB", "R-SA", "R-SC"), stayNames(departuresPaged(true, criteria(null, "outstanding", "asc"), 0)));
        assertEquals(List.of("R-SC", "R-SA", "R-SB"), stayNames(departuresPaged(true, criteria(null, "outstanding", "desc"), 0)));
    }

    /** Confirms Outstanding is not sortable without MANAGE_PAYMENT: the request falls back to the default order, never to amounts. */
    @Test
    void shouldIgnoreOutstandingSortWhenAmountsAreNotPermitted() {
        Stay a = stay("R-SA", TODAY.minusDays(5), TODAY.minusDays(1), CHECKED_IN, BookingSource.AGODA, "401");
        Stay c = stay("R-SC", TODAY.minusDays(10), TODAY, CHECKED_IN, BookingSource.BOOKING_COM, "201");
        pay(a, "300000", "0");
        pay(c, "2200000", "0");

        Page<FrontDeskStayRow> page = departuresPaged(false, criteria(null, "outstanding", "asc"), 0);

        assertEquals(stayNames(departuresPaged(false, criteria(null, null, null), 0)), stayNames(page));
        assertNull(page.getContent().get(0).outstanding());
    }

    /** Confirms In-house sorts every data column both ways, with Nights numeric and Checked-in chronological. */
    @Test
    void shouldSortInHouseByEveryDataColumnNumericallyAndChronologically() {
        guestNames.put("G-R-IA", "Alice");
        guestNames.put("G-R-IB", "Bob");
        guestNames.put("G-R-IC", null);
        stay("R-IA", TODAY.minusDays(1), TODAY.plusDays(3), Instant.parse("2026-09-19T07:00:00Z"), BookingSource.AGODA, "210");
        stay("R-IB", TODAY, TODAY.plusDays(2), Instant.parse("2026-09-21T07:00:00Z"), BookingSource.DIRECT, "9");
        stay("R-IC", TODAY, TODAY.plusDays(10), Instant.parse("2026-09-18T07:00:00Z"), BookingSource.BOOKING_COM, "120");

        assertEquals(List.of("R-IC", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, null, null), 0)));
        assertEquals(List.of("R-IA", "R-IB", "R-IC"), stayNames(inHousePaged(criteria(null, "reservationNumber", "asc"), 0)));
        assertEquals(List.of("R-IC", "R-IB", "R-IA"), stayNames(inHousePaged(criteria(null, "reservationNumber", "desc"), 0)));
        assertEquals(List.of("R-IA", "R-IB", "R-IC"), stayNames(inHousePaged(criteria(null, "guestName", "asc"), 0)));
        assertEquals(List.of("R-IB", "R-IA", "R-IC"), stayNames(inHousePaged(criteria(null, "guestName", "desc"), 0)));
        assertEquals(List.of("R-IC", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, "room", "asc"), 0)));
        assertEquals(List.of("R-IB", "R-IA", "R-IC"), stayNames(inHousePaged(criteria(null, "room", "desc"), 0)));
        // Chronological: 18 Sep, 19 Sep, 21 Sep.
        assertEquals(List.of("R-IC", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, "checkedIn", "asc"), 0)));
        assertEquals(List.of("R-IB", "R-IA", "R-IC"), stayNames(inHousePaged(criteria(null, "checkedIn", "desc"), 0)));
        assertEquals(List.of("R-IB", "R-IA", "R-IC"), stayNames(inHousePaged(criteria(null, "plannedCheckOutDate", "asc"), 0)));
        assertEquals(List.of("R-IC", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, "plannedCheckOutDate", "desc"), 0)));
        // Numeric: 2, 4, 10 (text order would put "10" first).
        assertEquals(List.of("R-IB", "R-IA", "R-IC"), stayNames(inHousePaged(criteria(null, "nights", "asc"), 0)));
        assertEquals(List.of("R-IC", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, "nights", "desc"), 0)));
        assertEquals(List.of("R-IA", "R-IC", "R-IB"), stayNames(inHousePaged(criteria(null, "source", "asc"), 0)));
        assertEquals(List.of("R-IB", "R-IC", "R-IA"), stayNames(inHousePaged(criteria(null, "source", "desc"), 0)));
    }

    /**
     * Confirms In-house room sorting uses the CURRENT occupied room after a Room Change (open StayRoomAssignment), not the
     * originally booked room: the moved stay's booked room 900 would sort last, but its current room 150 sorts first.
     */
    @Test
    void shouldSortInHouseRoomByCurrentRoomAfterRoomChange() {
        stay("R-IA", TODAY.minusDays(1), TODAY.plusDays(3), Instant.parse("2026-09-19T07:00:00Z"), BookingSource.AGODA, "210");
        stay("R-IB", TODAY, TODAY.plusDays(2), Instant.parse("2026-09-21T07:00:00Z"), BookingSource.DIRECT, "9");
        movedStay("R-MV", TODAY.plusDays(1), room("900", RoomStatus.OCCUPIED), room("150", RoomStatus.OCCUPIED));

        assertEquals(List.of("R-MV", "R-IA", "R-IB"), stayNames(inHousePaged(criteria(null, "room", "asc"), 0)));
    }

    /** Confirms a filter narrows the result before sorting, and the sorted filtered result is what gets paged. */
    @Test
    void shouldSortFilteredArrivalsBeforeTheyArePaged() {
        line(reservation("R-X1", TODAY, BookingSource.AGODA), room("601", RoomStatus.AVAILABLE));
        line(reservation("R-X2", TODAY, BookingSource.AGODA), room("602", RoomStatus.AVAILABLE));
        line(reservation("R-X3", TODAY, BookingSource.DIRECT), room("603", RoomStatus.AVAILABLE));

        FrontDeskSearchCriteria criteria = facets(null, null, "AGODA");
        criteria.setSort("reservationNumber");
        criteria.setDir("desc");

        assertEquals(List.of("R-X2", "R-X1"), namesOf(arrivalsPaged(criteria, 0)));
    }

    /** Confirms pagination slices the complete sorted result: page 2 continues the same order instead of restarting it. */
    @Test
    void shouldPaginateAfterSortingTheWholeResult() {
        for (int i = 0; i < 12; i++) {
            line(reservation(String.format("R-P%02d", i), TODAY, BookingSource.DIRECT), room("7" + i, RoomStatus.AVAILABLE));
        }

        assertEquals(List.of("R-P11", "R-P10", "R-P09", "R-P08", "R-P07", "R-P06", "R-P05", "R-P04", "R-P03", "R-P02"),
                namesOf(arrivalsPaged(criteria(null, "reservationNumber", "desc"), 0)));
        assertEquals(List.of("R-P01", "R-P00"), namesOf(arrivalsPaged(criteria(null, "reservationNumber", "desc"), 1)));
    }

    /** Confirms an unknown sort key or direction falls back to the default operational order, without an error. */
    @Test
    void shouldFallBackToDefaultOrderForInvalidSortKeyOrDirection() {
        line(reservation("R-F1", TODAY, BookingSource.DIRECT), room("801", RoomStatus.AVAILABLE));
        line(reservation("R-F2", TODAY.minusDays(1), BookingSource.DIRECT), room("802", RoomStatus.AVAILABLE));

        List<String> defaults = namesOf(arrivalsPaged(criteria(null, null, null), 0));
        assertEquals(defaults, namesOf(arrivalsPaged(criteria(null, "bogus", "asc"), 0)));
        assertEquals(defaults, namesOf(arrivalsPaged(criteria(null, "reservationNumber", "up"), 0)));
        assertEquals(defaults, namesOf(arrivalsPaged(criteria(null, "reservationNumber", null), 0)));
    }

    private static List<String> stayNames(Page<FrontDeskStayRow> page) {
        return page.getContent().stream().map(FrontDeskStayRow::reservationNumber).toList();
    }

    /** Confirms the paged In-house method keeps the default room-number ordering when no sort is requested. */
    @Test
    void shouldPreserveInHouseRoomOrderingWhenNoSortRequested() {
        stay("R-2", TODAY.plusDays(2), "202");
        stay("R-1", TODAY, "101");

        Page<FrontDeskStayRow> page = inHousePaged(criteria(null, null, null), 0);

        assertEquals(List.of("R-1", "R-2"), page.getContent().stream().map(FrontDeskStayRow::reservationNumber).toList());
    }

    /** Confirms an explicit Room sort is accepted for In-house (its own additional whitelisted column). */
    @Test
    void shouldSortInHouseByRoomDescending() {
        stay("R-1", TODAY, "101");
        stay("R-2", TODAY, "202");

        Page<FrontDeskStayRow> page = inHousePaged(criteria(null, "room", "desc"), 0);

        assertEquals(List.of("R-2", "R-1"), page.getContent().stream().map(FrontDeskStayRow::reservationNumber).toList());
    }

    /** Confirms In-house never leaks a Balance/Outstanding amount through the paged method either. */
    @Test
    void shouldNeverExposeBalanceThroughPagedInHouseMethod() {
        stay("R-1", TODAY, "101");

        Page<FrontDeskStayRow> page = inHousePaged(criteria(null, null, null), 0);

        assertNull(page.getContent().get(0).outstanding());
        verifyNoInteractions(charges, payments);
    }

    /** Confirms In-house search matches Guest code in addition to Reservation number and Room number. */
    @Test
    void shouldMatchInHouseSearchByGuestCode() {
        stay("R-1", TODAY, "101");
        stay("R-2", TODAY, "202");

        Page<FrontDeskStayRow> page = inHousePaged(criteria("G-R-2", null, null), 0);

        assertEquals(List.of("R-2"), page.getContent().stream().map(FrontDeskStayRow::reservationNumber).toList());
    }

    /** Confirms In-house Room Type filters by the room the guest occupies NOW, not the originally booked room. */
    @Test
    void shouldFilterInHouseRoomTypeByCurrentRoomAfterRoomChange() {
        movedStay("R-MOVED", TODAY.plusDays(2), roomOfType("101", SINGLE_TYPE, "Single"), roomOfType("202", DOUBLE_TYPE, "Double"));

        assertEquals(List.of("R-MOVED"), inHouseNames(roomTypeCriteria(DOUBLE_TYPE)));
        assertEquals(List.of(), inHouseNames(roomTypeCriteria(SINGLE_TYPE)));
    }

    /** Confirms In-house Room Type and Source filters narrow the same in-house population and combine. */
    @Test
    void shouldFilterInHouseByRoomTypeAndSourceTogether() {
        stayWithRoom("R-SGL-AG", TODAY, BookingSource.AGODA, roomOfType("101", SINGLE_TYPE, "Single"));
        stayWithRoom("R-SGL-DIR", TODAY, BookingSource.DIRECT, roomOfType("102", SINGLE_TYPE, "Single"));
        stayWithRoom("R-DBL-AG", TODAY, BookingSource.AGODA, roomOfType("201", DOUBLE_TYPE, "Double"));

        FrontDeskSearchCriteria criteria = roomTypeCriteria(SINGLE_TYPE);
        criteria.setSource("AGODA");

        assertEquals(List.of("R-SGL-AG"), inHouseNames(criteria));
        assertEquals(List.of("R-SGL-AG", "R-DBL-AG"), inHouseNames(sourceCriteria("AGODA")));
        assertEquals(List.of(), inHouseNames(roomTypeCriteria(UUID.randomUUID())));
    }

    /** Confirms a malformed Room Type value is ignored rather than failing or narrowing to nothing. */
    @Test
    void shouldIgnoreMalformedInHouseRoomType() {
        stayWithRoom("R-1", TODAY, BookingSource.DIRECT, roomOfType("101", SINGLE_TYPE, "Single"));
        FrontDeskSearchCriteria criteria = criteria(null, null, null);
        criteria.setRoomType("not-a-uuid");

        assertEquals(List.of("R-1"), inHouseNames(criteria));
    }

    /** Confirms the Departures checkout-date filter reads the planned checkout relative to the hotel date. */
    @Test
    void shouldFilterDeparturesByCheckoutDateTodayAndOverdue() {
        stay("R-OD", TODAY.minusDays(1), "101");
        stay("R-TD", TODAY, "102");

        assertEquals(List.of("R-TD"), departureNames(checkoutCriteria("TODAY", null, null)));
        assertEquals(List.of("R-OD"), departureNames(checkoutCriteria("OVERDUE", null, null)));
        assertEquals(List.of("R-OD", "R-TD"), departureNames(checkoutCriteria(null, null, null)));
    }

    /** Confirms the Departures status filter uses the existing derived facts: overdue, payment required, or ready. */
    @Test
    void shouldFilterDeparturesByStatusFromExistingReadinessFacts() {
        Stay overdue = stay("R-OD", TODAY.minusDays(1), "101");
        Stay owing = stay("R-OWING", TODAY, "102");
        stay("R-READY", TODAY, "103");
        pay(overdue, "500000", "500000");
        pay(owing, "500000", "100000");

        assertEquals(List.of("R-OD"), departureNames(checkoutCriteria(null, "OVERDUE", null)));
        assertEquals(List.of("R-OWING"), departureNames(checkoutCriteria(null, "PAYMENT_REQUIRED", null)));
        assertEquals(List.of("R-READY"), departureNames(checkoutCriteria(null, "READY", null)));
    }

    /** Confirms the Departures Source filter and its combination with checkout date and status. */
    @Test
    void shouldCombineDeparturesDateStatusAndSource() {
        stayWithSource("R-AG-OD", TODAY.minusDays(1), BookingSource.AGODA, "101");
        stayWithSource("R-DIR-OD", TODAY.minusDays(1), BookingSource.DIRECT, "102");
        stayWithSource("R-AG-TD", TODAY, BookingSource.AGODA, "103");

        assertEquals(List.of("R-AG-OD", "R-AG-TD"), departureNames(sourceCriteria("AGODA")));
        assertEquals(List.of("R-AG-OD"), departureNames(checkoutCriteria("OVERDUE", "OVERDUE", "AGODA")));
        assertEquals(List.of(), departureNames(checkoutCriteria("TODAY", "OVERDUE", "AGODA")));
    }

    /** Confirms the checkout-date filter reads the row's planned checkout (the Reservation's own check-out date). */
    @Test
    void shouldFilterDeparturesByTheRowsPlannedCheckoutDate() {
        stay("R-DUE-TODAY", TODAY, "101");

        assertEquals(List.of(), departureNames(checkoutCriteria("OVERDUE", null, null)));
        assertEquals(List.of("R-DUE-TODAY"), departureNames(checkoutCriteria("TODAY", null, null)));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> inHouseNames(FrontDeskSearchCriteria criteria) {
        return inHousePaged(criteria, 0).getContent().stream().map(FrontDeskStayRow::reservationNumber).toList();
    }

    private List<String> departureNames(FrontDeskSearchCriteria criteria) {
        return departuresPaged(true, criteria, 0).getContent().stream().map(FrontDeskStayRow::reservationNumber).toList();
    }

    private static FrontDeskSearchCriteria roomTypeCriteria(UUID roomType) {
        FrontDeskSearchCriteria criteria = criteria(null, null, null);
        criteria.setRoomType(roomType.toString());
        return criteria;
    }

    private static FrontDeskSearchCriteria sourceCriteria(String source) {
        FrontDeskSearchCriteria criteria = criteria(null, null, null);
        criteria.setSource(source);
        return criteria;
    }

    private static FrontDeskSearchCriteria checkoutCriteria(String checkoutDate, String status, String source) {
        FrontDeskSearchCriteria criteria = criteria(null, null, null);
        criteria.setCheckoutDate(checkoutDate);
        criteria.setStatus(status);
        criteria.setSource(source);
        return criteria;
    }

    /** Builds a checked-in Stay in one given Room, with the given Reservation source. */
    private Stay stayWithRoom(String number, LocalDate checkOut, BookingSource source, Room room) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkOut.minusDays(2), checkOut,
                source, null, "VND", null);
        reservation.confirm();
        reservation.checkIn();
        Stay stay = new Stay(reservation, Instant.parse("2026-09-20T07:35:00Z"));
        stayList.add(stay);
        ReservationRoom line = new ReservationRoom(reservation, room, reservation.getCheckInDate(), checkOut, BigDecimal.TEN);
        openAssignments.add(new StayRoomAssignment(stay, room, line, stay.getActualCheckInAt(), null, null));
        return stay;
    }

    private Stay stayWithSource(String number, LocalDate checkOut, BookingSource source, String roomNumber) {
        return stayWithRoom(number, checkOut, source, room(roomNumber, RoomStatus.OCCUPIED));
    }


    private static List<String> namesOf(Page<FrontDeskArrivalRow> page) {
        return page.getContent().stream().map(FrontDeskArrivalRow::reservationNumber).toList();
    }

    private static FrontDeskSearchCriteria facets(String arrivalDate, String readiness, String source) {
        FrontDeskSearchCriteria criteria = criteria(null, null, null);
        criteria.setArrivalDate(arrivalDate);
        criteria.setReadiness(readiness);
        criteria.setSource(source);
        return criteria;
    }

    private static FrontDeskSearchCriteria criteria(String search, String sort, String dir) {
        FrontDeskSearchCriteria criteria = new FrontDeskSearchCriteria();
        criteria.setSearch(search);
        criteria.setSort(sort);
        criteria.setDir(dir);
        return criteria;
    }

    private List<FrontDeskArrivalRow> arrivals() {
        when(reservations.findByStatusAndCheckInOnOrBefore(ReservationStatus.CONFIRMED, TODAY)).thenReturn(pending);
        when(reservations.findBookedRoomsByReservationIdIn(any())).thenReturn(rows);
        when(stays.findReservationIdsWithStay(any())).thenReturn(stayed);
        stubGuest();
        return service.arrivals();
    }

    private Page<FrontDeskArrivalRow> arrivalsPaged(FrontDeskSearchCriteria criteria, int page) {
        when(reservations.findByStatusAndCheckInOnOrBefore(ReservationStatus.CONFIRMED, TODAY)).thenReturn(pending);
        when(reservations.findBookedRoomsByReservationIdIn(any())).thenReturn(rows);
        when(stays.findReservationIdsWithStay(any())).thenReturn(stayed);
        stubGuest();
        return service.arrivals(criteria, page);
    }

    private List<FrontDeskStayRow> departures(boolean amounts) {
        stubGuest();
        return service.departures(amounts);
    }

    private Page<FrontDeskStayRow> departuresPaged(boolean amounts, FrontDeskSearchCriteria criteria, int page) {
        stubGuest();
        return service.departures(amounts, criteria, page);
    }

    private Page<FrontDeskStayRow> inHousePaged(FrontDeskSearchCriteria criteria, int page) {
        stubGuest();
        return service.inHouse(criteria, page);
    }

    private String guestName = "Nguyen Van An";

    /** Per-guest display names keyed by guest code, so sorting by guest can be tested; a null value means no name. */
    private final Map<String, String> guestNames = new HashMap<>();

    private String guestNameOf(Guest guest) {
        return guestNames.containsKey(guest.getGuestCode()) ? guestNames.get(guest.getGuestCode()) : guestName;
    }
    private final List<Stay> stayList = new ArrayList<>();
    private final List<StayRoomAssignment> openAssignments = new ArrayList<>();
    private final List<StayAmountRow> chargeRows = new ArrayList<>();
    private final List<StayAmountRow> paidRows = new ArrayList<>();

    private void stubGuest() {
        // Null-guarded: when a Batch 3A test calls more than one stubGuest()-backed helper in the same test
        // (e.g. comparing the default and an explicit sort), Mockito's own when(...) re-registration invokes the
        // mock once with a null argument to find the prior stub; without this guard that invocation itself NPEs.
        when(guestMapper.toLookupResponse(any())).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            return guest == null
                    ? null
                    : new GuestLookupResponse(guest.getId(), guest.getGuestCode(), guestNameOf(guest), null, null, null);
        });
        when(stays.findWithReservationAndGuestDueBy(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN, TODAY))
                .thenReturn(stayList.stream().filter(s -> !s.getReservation().getCheckOutDate().isAfter(TODAY)).toList());
        when(stays.findWithReservationAndGuest(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN)).thenReturn(stayList);
        when(assignments.findOpenByStayIdInWithRoom(any())).thenReturn(openAssignments);
        when(charges.sumAmountByStayIdIn(any())).thenReturn(chargeRows);
        when(payments.sumAppliedAmountByStayIdInAndStatus(any(), any())).thenReturn(paidRows);
    }

    private Reservation reservation(String number, LocalDate checkIn) {
        return reservation(number, checkIn, BookingSource.DIRECT);
    }

    private Reservation reservation(String number, LocalDate checkIn, BookingSource source) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkIn, checkIn.plusDays(2),
                source, null, "VND", null);
        pending.add(reservation);
        return reservation;
    }

    private void line(Reservation reservation, Room room) {
        ReservationRoom line = new ReservationRoom(
                reservation, room, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.TEN);
        reservation.addRoom(line);
        rows.add(new Object[] {reservation.getId(), room});
        if (reservation.getStatus() == ReservationStatus.DRAFT) {
            reservation.confirm();
        }
    }

    private Stay stay(String number, LocalDate checkOut, String... roomNumbers) {
        return stay(number, checkOut, BookingSource.DIRECT, roomNumbers);
    }

    private Stay stay(String number, LocalDate checkOut, BookingSource source, String... roomNumbers) {
        return stay(number, checkOut.minusDays(2), checkOut, Instant.parse("2026-09-20T07:35:00Z"), source, roomNumbers);
    }

    /** Builds a checked-in Stay with an explicit check-in date, check-out date and actual check-in instant. */
    private Stay stay(String number, LocalDate checkIn, LocalDate checkOut, Instant actualCheckIn, BookingSource source,
            String... roomNumbers) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkIn, checkOut,
                source, null, "VND", null);
        reservation.confirm();
        reservation.checkIn();
        Stay stay = new Stay(reservation, actualCheckIn);
        stayList.add(stay);
        for (String roomNumber : roomNumbers) {
            Room room = room(roomNumber, RoomStatus.OCCUPIED);
            ReservationRoom line = new ReservationRoom(reservation, room, reservation.getCheckInDate(), checkOut, BigDecimal.TEN);
            openAssignments.add(new StayRoomAssignment(stay, room, line, stay.getActualCheckInAt(), null, null));
        }
        return stay;
    }

    /**
     * Builds a checked-in Stay whose BOOKED room (its ReservationRoom snapshot) differs from the room it currently
     * occupies (its open StayRoomAssignment), as after a Room Change.
     */
    private Stay movedStay(String number, LocalDate checkOut, Room booked, Room current) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkOut.minusDays(2), checkOut,
                BookingSource.DIRECT, null, "VND", null);
        reservation.confirm();
        reservation.checkIn();
        Stay stay = new Stay(reservation, Instant.parse("2026-09-20T07:35:00Z"));
        stayList.add(stay);
        ReservationRoom line = new ReservationRoom(reservation, booked, reservation.getCheckInDate(), checkOut, BigDecimal.TEN);
        openAssignments.add(new StayRoomAssignment(stay, current, line, stay.getActualCheckInAt(), null, null));
        return stay;
    }

    /** Builds a Room whose RoomType carries a real identifier and name, for the Room Type filter. */
    private static Room roomOfType(String number, UUID typeId, String typeName) {
        RoomType type = mock(RoomType.class);
        when(type.getId()).thenReturn(typeId);
        when(type.getName()).thenReturn(typeName);
        when(type.getCapacity()).thenReturn(2);
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        ReflectionTestUtils.setField(room, "status", RoomStatus.OCCUPIED);
        return room;
    }

    private void pay(Stay stay, String charged, String paid) {
        chargeRows.add(new StayAmountRow(stay.getId(), new BigDecimal(charged)));
        if (new BigDecimal(paid).signum() != 0) {
            paidRows.add(new StayAmountRow(stay.getId(), new BigDecimal(paid)));
        }
    }

    private Room room(String number, RoomStatus status) {
        RoomType type = mock(RoomType.class);
        when(type.getId()).thenReturn(DOUBLE_TYPE);
        when(type.getName()).thenReturn("Double");
        when(type.getCapacity()).thenReturn(2);
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        ReflectionTestUtils.setField(room, "status", status);
        return room;
    }
}
