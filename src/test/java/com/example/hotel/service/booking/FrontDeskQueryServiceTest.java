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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies Front Desk grouping, ordering, derived attention, current-room semantics, money visibility and query use. */
class FrontDeskQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

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

    // ------------------------------------------------------------------ helpers

    private List<FrontDeskArrivalRow> arrivals() {
        when(reservations.findByStatusAndCheckInOnOrBefore(ReservationStatus.CONFIRMED, TODAY)).thenReturn(pending);
        when(reservations.findBookedRoomsByReservationIdIn(any())).thenReturn(rows);
        when(stays.findReservationIdsWithStay(any())).thenReturn(stayed);
        stubGuest();
        return service.arrivals();
    }

    private List<FrontDeskStayRow> departures(boolean amounts) {
        stubGuest();
        return service.departures(amounts);
    }

    private String guestName = "Nguyen Van An";
    private final List<Stay> stayList = new ArrayList<>();
    private final List<StayRoomAssignment> openAssignments = new ArrayList<>();
    private final List<StayAmountRow> chargeRows = new ArrayList<>();
    private final List<StayAmountRow> paidRows = new ArrayList<>();

    private void stubGuest() {
        when(guestMapper.toLookupResponse(any())).thenAnswer(invocation -> {
            Guest guest = invocation.getArgument(0);
            return new GuestLookupResponse(guest.getId(), guest.getGuestCode(), guestName, null, null, null);
        });
        when(stays.findWithReservationAndGuestDueBy(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN, TODAY))
                .thenReturn(stayList.stream().filter(s -> !s.getReservation().getCheckOutDate().isAfter(TODAY)).toList());
        when(stays.findWithReservationAndGuest(StayStatus.CHECKED_IN, ReservationStatus.CHECKED_IN)).thenReturn(stayList);
        when(assignments.findOpenByStayIdInWithRoom(any())).thenReturn(openAssignments);
        when(charges.sumAmountByStayIdIn(any())).thenReturn(chargeRows);
        when(payments.sumAppliedAmountByStayIdInAndStatus(any(), any())).thenReturn(paidRows);
    }

    private Reservation reservation(String number, LocalDate checkIn) {
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkIn, checkIn.plusDays(2),
                BookingSource.DIRECT, null, "VND", null);
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
        Guest guest = Guest.create(UUID.randomUUID(), "G-" + number, "Ann", "Lee", null, null, "Vietnam", null, null);
        Reservation reservation = new Reservation(UUID.randomUUID(), number, guest, checkOut.minusDays(2), checkOut,
                BookingSource.DIRECT, null, "VND", null);
        reservation.confirm();
        reservation.checkIn();
        Stay stay = new Stay(reservation, Instant.parse("2026-09-20T07:35:00Z"));
        stayList.add(stay);
        for (String roomNumber : roomNumbers) {
            Room room = room(roomNumber, RoomStatus.OCCUPIED);
            ReservationRoom line = new ReservationRoom(reservation, room, reservation.getCheckInDate(), checkOut, BigDecimal.TEN);
            openAssignments.add(new StayRoomAssignment(stay, room, line, stay.getActualCheckInAt(), null, null));
        }
        return stay;
    }

    private void pay(Stay stay, String charged, String paid) {
        chargeRows.add(new StayAmountRow(stay.getId(), new BigDecimal(charged)));
        if (new BigDecimal(paid).signum() != 0) {
            paidRows.add(new StayAmountRow(stay.getId(), new BigDecimal(paid)));
        }
    }

    private Room room(String number, RoomStatus status) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn("Double");
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        ReflectionTestUtils.setField(room, "status", status);
        return room;
    }
}
