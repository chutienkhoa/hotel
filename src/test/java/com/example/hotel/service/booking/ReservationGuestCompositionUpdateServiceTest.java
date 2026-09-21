package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.exception.GuestCompositionUpdateException;
import com.example.hotel.exception.GuestCompositionUpdateException.Reason;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies the dedicated CONFIRMED-reservation guest-composition update: state, fields, capacity, atomicity, audit. */
class ReservationGuestCompositionUpdateServiceTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final ReservationService service = new ReservationService(
            reservations, guests, mock(RoomRepository.class), stays, mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class), audits, new ReservationMapper(), mock(ReservationNumberGenerator.class),
            mock(StayBalanceService.class), Clock.fixed(IN.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final Guest primary = guest("G-1");
    private final Guest second = guest("G-2");
    private final Guest third = guest("G-3");
    private final UUID actor = UUID.randomUUID();

    /** Authenticates and stubs the known guests. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null, List.of()));
        when(guests.findAllById(any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(0);
            return List.of(primary, second, third).stream().filter(g -> ids.contains(g.getId())).toList();
        });
        when(stays.existsByReservationId(any())).thenReturn(false);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- state

    /** Confirms a CONFIRMED reservation with no Stay can update its composition. */
    @Test
    void shouldUpdateAConfirmedReservationWithoutAStay() {
        Reservation reservation = confirmed(2, 1, room("101", 2), room("102", 1));

        service.updateConfirmedGuestComposition(reservation.getId(), request(3, 2, second));

        assertEquals(3, reservation.getAdultCount());
        assertEquals(2, reservation.getChildCount());
        assertEquals(List.of(second), reservation.getAccompanyingGuests());
    }

    /** Confirms every other status is rejected by this dedicated operation, changing nothing. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT"})
    void shouldRejectOtherStatuses(ReservationStatus status) {
        Reservation reservation = confirmed(2, 0, room("101", 2));
        ReflectionTestUtils.setField(reservation, "status", status);

        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.getId(), request(2, 5, second)));

        assertEquals(Reason.RESERVATION_NOT_CONFIRMED, exception.getUpdateReason());
        assertEquals(0, reservation.getChildCount());
        assertTrue(reservation.getAccompanyingGuests().isEmpty());
        verify(audits, never()).save(any());
    }

    /** Confirms an existing Stay blocks the update even if abnormal data leaves the status CONFIRMED. */
    @Test
    void shouldRejectWhenAStayExists() {
        Reservation reservation = confirmed(2, 0, room("101", 2));
        when(stays.existsByReservationId(reservation.getId())).thenReturn(true);

        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.getId(), request(2, 3)));

        assertEquals(Reason.STAY_ALREADY_EXISTS, exception.getUpdateReason());
        assertEquals(0, reservation.getChildCount());
        verify(audits, never()).save(any());
    }

    // --------------------------------------------------------------- fields

    /** Confirms accompanying guests can be added, removed and replaced. */
    @Test
    void shouldAddRemoveAndReplaceAccompanyingGuests() {
        Reservation reservation = confirmed(2, 0, room("101", 2));

        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 0, second));
        assertEquals(List.of(second), reservation.getAccompanyingGuests());
        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 0, second, third));
        assertEquals(List.of(second, third), reservation.getAccompanyingGuests());
        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 0, third));
        assertEquals(List.of(third), reservation.getAccompanyingGuests());
        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 0));
        assertTrue(reservation.getAccompanyingGuests().isEmpty());
    }

    /** Confirms only the composition changes: primary guest, dates, rooms, rates, source, currency and notes stay. */
    @Test
    void shouldChangeOnlyTheGuestComposition() {
        Room doubleRoom = room("101", 2);
        Reservation reservation = confirmed(2, 0, doubleRoom);
        BigDecimal total = reservation.getTotalAmount();

        service.updateConfirmedGuestComposition(reservation.getId(), request(1, 4, second));

        assertSame(primary, reservation.getGuest());
        assertEquals(IN, reservation.getCheckInDate());
        assertEquals(OUT, reservation.getCheckOutDate());
        assertEquals(BookingSource.BOOKING_COM, reservation.getSource());
        assertEquals("BK-1", reservation.getOtaBookingReference());
        assertEquals("VND", reservation.getCurrency());
        assertEquals("keep", reservation.getNotes());
        assertEquals(0, total.compareTo(reservation.getTotalAmount()));
        assertEquals(1, reservation.getRooms().size());
        assertSame(doubleRoom, reservation.getRooms().get(0).getRoom());
        assertEquals(0, new BigDecimal("1000000").compareTo(reservation.getRooms().get(0).getNightlyRate()));
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    /** Confirms the request type has no way to carry a Primary Guest (or any other Reservation field). */
    @Test
    void shouldNotAcceptAPrimaryGuestOrOtherFields() {
        List<String> components = java.util.Arrays.stream(GuestCompositionUpdateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertEquals(List.of("adultCount", "childCount", "accompanyingGuestIds"), components);
    }

    // ----------------------------------------------------------- validation

    /** Confirms invalid counts and guest input are rejected, leaving every part of the composition unchanged. */
    @Test
    void shouldRejectInvalidInputWithoutAnyChange() {
        Reservation reservation = confirmed(2, 1, room("101", 2), room("102", 1));
        withAccompanying(reservation, second);

        assertReason(reservation, request(0, 1), Reason.INVALID_ADULT_COUNT);
        assertReason(reservation, request(2, -1), Reason.INVALID_CHILD_COUNT);
        assertReason(reservation, new GuestCompositionUpdateRequest(null, 1, List.of()), Reason.INVALID_ADULT_COUNT);
        assertReason(reservation, new GuestCompositionUpdateRequest(2, null, List.of()), Reason.INVALID_CHILD_COUNT);
        assertReason(reservation, new GuestCompositionUpdateRequest(2, 1, List.of(UUID.randomUUID())), Reason.GUEST_NOT_FOUND);
        assertReason(reservation, request(2, 1, third, third), Reason.DUPLICATE_GUEST);
        assertReason(reservation, request(2, 1, primary), Reason.PRIMARY_GUEST_AS_ACCOMPANYING);

        assertEquals(2, reservation.getAdultCount());
        assertEquals(1, reservation.getChildCount());
        assertEquals(List.of(second), reservation.getAccompanyingGuests());
        verify(audits, never()).save(any());
    }

    // ------------------------------------------------------------- capacity

    /** Confirms exact and spare adult capacity succeed, and children/profiles never affect it. */
    @Test
    void shouldAcceptExactAndSpareCapacityIgnoringChildrenAndProfiles() {
        Reservation exact = confirmed(1, 0, room("101", 2), room("102", 1));
        service.updateConfirmedGuestComposition(exact.getId(), request(3, 5, second, third));
        assertEquals(3, exact.getAdultCount());

        Reservation spare = confirmed(1, 0, room("103", 2));
        service.updateConfirmedGuestComposition(spare.getId(), request(1, 9));
        assertEquals(9, spare.getChildCount());
    }

    /** Confirms insufficient capacity rejects the update with structured arguments, unchanged and unaudited. */
    @Test
    void shouldRejectInsufficientCapacityAtomically() {
        Reservation reservation = confirmed(2, 1, room("101", 2));
        withAccompanying(reservation, second);

        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.getId(), request(3, 4, third)));

        assertEquals(Reason.INSUFFICIENT_ADULT_CAPACITY, exception.getUpdateReason());
        assertEquals(List.of(3, 2), exception.getArguments());
        assertEquals(2, reservation.getAdultCount());
        assertEquals(1, reservation.getChildCount());
        assertEquals(List.of(second), reservation.getAccompanyingGuests());
        verify(audits, never()).save(any());
    }

    /** Confirms a null capacity and a missing RoomType are CAPACITY_NOT_CONFIGURED, never zero, unlimited or skipped. */
    @Test
    void shouldRejectUnconfiguredCapacity() {
        Reservation nullCapacity = confirmed(1, 0, room("101", 2), room("102", null));
        Reservation noType = confirmed(1, 0, Room.create(UUID.randomUUID(), "103", null, "1"));

        for (Reservation reservation : List.of(nullCapacity, noType)) {
            GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                    () -> service.updateConfirmedGuestComposition(reservation.getId(), request(1, 1)));
            assertEquals(Reason.CAPACITY_NOT_CONFIGURED, exception.getUpdateReason());
            assertEquals(0, reservation.getChildCount());
        }
        verify(audits, never()).save(any());
    }

    /** Confirms the shared AdultCapacityRules decides: the service outcome equals the rule's result for the same set. */
    @Test
    void shouldUseTheSharedCapacityRule() {
        Room a = room("101", 2);
        Room b = room("102", 1);
        for (int adults = 1; adults <= 5; adults++) {
            Reservation reservation = confirmed(1, 0, a, b);
            boolean expected = AdultCapacityRules.evaluate(adults, List.of(a, b)).valid();
            int attempt = adults;
            boolean accepted;
            try {
                service.updateConfirmedGuestComposition(reservation.getId(), request(attempt, 0));
                accepted = true;
            } catch (GuestCompositionUpdateException exception) {
                accepted = false;
            }
            assertEquals(expected, accepted, "adults=" + adults);
        }
    }

    // ---------------------------------------------------- locking and audit

    /** Confirms the Reservation row is locked BEFORE the room set is read, and capacity uses that locked view. */
    @Test
    void shouldLockTheReservationBeforeReadingTheRoomSet() {
        Reservation reservation = confirmed(1, 0, room("101", 2));

        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 0));

        org.mockito.InOrder order = inOrder(reservations, stays);
        order.verify(reservations).findByIdForUpdate(reservation.getId());
        order.verify(stays).existsByReservationId(reservation.getId());
        order.verify(reservations).findBookedRoomsByReservationIdIn(List.of(reservation.getId()));
        verify(reservations, never()).findById(any());
    }

    /** Confirms a successful update writes exactly one UPDATE_GUEST_COMPOSITION audit entry with before/after. */
    @Test
    void shouldAuditExactlyOnceOnSuccess() {
        Reservation reservation = confirmed(1, 0, room("101", 2));

        service.updateConfirmedGuestComposition(reservation.getId(), request(2, 1, second));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(1)).save(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals("UPDATE_GUEST_COMPOSITION", ReflectionTestUtils.getField(log, "action"));
        assertEquals(reservation.getId(), ReflectionTestUtils.getField(log, "entityId"));
        assertEquals(actor, ReflectionTestUtils.getField(log, "userId"));
        assertEquals("adults=1, children=0, accompanying=0", ReflectionTestUtils.getField(log, "oldValue"));
        assertEquals("adults=2, children=1, accompanying=1", ReflectionTestUtils.getField(log, "newValue"));
    }

    // ------------------------------------------------- readiness / check-in

    /** Confirms readiness is derived from the updated count (nothing is persisted) and is blocked when capacity is short. */
    @Test
    void shouldLetReadinessNaturallyUseTheUpdatedAdultCount() {
        Room doubleRoom = room("101", 2);
        Reservation reservation = confirmed(2, 0, doubleRoom);
        assertTrue(readiness(reservation).blockers().isEmpty());

        // A larger party is rejected by the update itself, so the stored count (and thus readiness) stays valid.
        assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.getId(), request(3, 0)));
        assertTrue(readiness(reservation).blockers().isEmpty());

        // Legacy/abnormal data with a larger count is what readiness would report, from the same shared rule.
        ReflectionTestUtils.setField(reservation, "adultCount", 3);
        assertEquals(ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY, readiness(reservation).blockers().get(0).code());
    }

    /** Confirms a phase-by-phase valid update is what check-in then sees (adults are read from the Reservation). */
    @Test
    void shouldLetCheckInConsumeTheUpdatedCountThroughTheSharedRule() {
        Room doubleRoom = room("101", 2);
        Room singleRoom = room("102", 1);
        Reservation reservation = confirmed(2, 0, doubleRoom, singleRoom);

        service.updateConfirmedGuestComposition(reservation.getId(), request(3, 0));

        assertEquals(3, reservation.getAdultCount());
        assertTrue(AdultCapacityRules.evaluate(reservation.getAdultCount(), List.of(doubleRoom, singleRoom)).valid());
        assertEquals(AdultCapacityRules.Outcome.INSUFFICIENT_ADULT_CAPACITY,
                AdultCapacityRules.evaluate(reservation.getAdultCount(), List.of(doubleRoom)).outcome());
    }

    // -------------------------------------------------------------- helpers

    /** Seeds an existing accompanying guest on an already confirmed fixture via the domain update operation. */
    private void withAccompanying(Reservation reservation, Guest guest) {
        reservation.updateConfirmedGuestComposition(
                reservation.getAdultCount(), reservation.getChildCount(), List.of(guest), actor);
    }

    private ArrivalReadiness readiness(Reservation reservation) {
        return ArrivalReadinessRules.evaluate(reservation.getStatus(), IN, IN, false, reservation.getAdultCount(),
                reservation.getRooms().stream().map(ReservationRoom::getRoom).toList(), true);
    }

    private void assertReason(Reservation reservation, GuestCompositionUpdateRequest request, Reason reason) {
        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.getId(), request));
        assertEquals(reason, exception.getUpdateReason());
    }

    private GuestCompositionUpdateRequest request(int adults, int children, Guest... accompanying) {
        List<UUID> ids = new ArrayList<>();
        for (Guest guest : accompanying) {
            ids.add(guest.getId());
        }
        return new GuestCompositionUpdateRequest(adults, children, ids);
    }

    private Reservation confirmed(int adults, int children, Room... rooms) {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, adults, children,
                BookingSource.BOOKING_COM, "BK-1", "VND", "keep");
        List<Object[]> rows = new ArrayList<>();
        for (Room room : rooms) {
            reservation.addRoom(new ReservationRoom(reservation, room, IN, OUT, new BigDecimal("1000000")));
            rows.add(new Object[] {reservation.getId(), room});
        }
        reservation.calculateTotal();
        reservation.confirm();
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findBookedRoomsByReservationIdIn(List.of(reservation.getId()))).thenReturn(rows);
        return reservation;
    }

    private static Room room(String number, Integer capacity) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn("T" + number);
        when(type.getCapacity()).thenReturn(capacity);
        return Room.create(UUID.randomUUID(), number, type, "1");
    }

    private static Guest guest(String code) {
        return Guest.create(UUID.randomUUID(), code, "Ann", "Lee", null, null, "Vietnam", null, null);
    }
}
