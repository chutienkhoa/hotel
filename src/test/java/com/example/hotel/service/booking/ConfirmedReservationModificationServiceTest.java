package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.exception.ConfirmedReservationModificationException;
import com.example.hotel.exception.ConfirmedReservationModificationException.Reason;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

/** Verifies the two controlled CONFIRMED-reservation modifications and their atomic invariants. */
class ConfirmedReservationModificationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private static final BigDecimal RATE_A = new BigDecimal("1000000");
    private static final BigDecimal RATE_B = new BigDecimal("750000");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final RoomAvailabilityService availability = mock(RoomAvailabilityService.class);
    private final PrepaymentService prepayments = mock(PrepaymentService.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final ReservationService service = new ReservationService(
            reservations,
            mock(GuestRepository.class),
            rooms,
            stays,
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            audits,
            new ReservationMapper(),
            mock(ReservationNumberGenerator.class),
            mock(StayBalanceService.class),
            availability,
            prepayments,
            Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final UUID actor = UUID.randomUUID();

    /** Authenticates a staff actor and supplies empty-conflict/default-prepayment behavior. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null, List.of()));
        when(availability.conflictedRoomIdsExcludingReservation(any(), any(), any(), any()))
                .thenReturn(Set.of());
        when(prepayments.activePaidTotal(any())).thenReturn(BigDecimal.ZERO);
        when(stays.existsByReservationId(any())).thenReturn(false);
    }

    /** Clears thread-local authentication. */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms dates, room snapshots and totals change together while Rooms and nightly rates remain identical. */
    @Test
    void shouldChangeDatesAndRecalculateFromPreservedRates() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);

        service.changeConfirmedDates(
                fixture.reservation().getId(), new ReservationDateChangeRequest(TODAY.plusDays(1), TODAY.plusDays(4)));

        Reservation reservation = fixture.reservation();
        assertEquals(TODAY.plusDays(1), reservation.getCheckInDate());
        assertEquals(TODAY.plusDays(4), reservation.getCheckOutDate());
        assertSame(fixture.roomA(), reservation.getRooms().get(0).getRoom());
        assertSame(fixture.roomB(), reservation.getRooms().get(1).getRoom());
        assertEquals(0, RATE_A.compareTo(reservation.getRooms().get(0).getNightlyRate()));
        assertEquals(0, RATE_B.compareTo(reservation.getRooms().get(1).getNightlyRate()));
        for (ReservationRoom line : reservation.getRooms()) {
            assertEquals(TODAY.plusDays(1), line.getCheckInDate());
            assertEquals(TODAY.plusDays(4), line.getCheckOutDate());
        }
        assertEquals(0, new BigDecimal("3000000").compareTo(reservation.getRooms().get(0).getTotalAmount()));
        assertEquals(0, new BigDecimal("2250000").compareTo(reservation.getRooms().get(1).getTotalAmount()));
        assertEquals(0, new BigDecimal("5250000").compareTo(reservation.getTotalAmount()));
    }

    /** Confirms every non-CONFIRMED lifecycle state is rejected by the date-change operation. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT"})
    void shouldRejectDateChangeOutsideConfirmed(ReservationStatus status) {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);
        ReflectionTestUtils.setField(fixture.reservation(), "status", status);

        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY, TODAY.plusDays(3))),
                Reason.RESERVATION_NOT_CONFIRMED);

        assertEquals(TODAY.plusDays(2), fixture.reservation().getCheckOutDate());
        verify(audits, never()).save(any());
    }

    /** Confirms an existing Stay closes both controlled operations even if status is anomalously still CONFIRMED. */
    @Test
    void shouldRejectBothOperationsWhenStayExists() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.AGODA);
        when(stays.existsByReservationId(fixture.reservation().getId())).thenReturn(true);

        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY, TODAY.plusDays(3))),
                Reason.STAY_ALREADY_EXISTS);
        assertReason(
                () -> service.correctOtaBookingReference(fixture.reservation().getId(),
                        new OtaReferenceCorrectionRequest("NEW")),
                Reason.STAY_ALREADY_EXISTS);
        verify(audits, never()).save(any());
    }

    /** Confirms arrival-day changes and recovery of an overdue arrival to today or later are allowed. */
    @Test
    void shouldAllowArrivalDayAndOverdueRecoveryToTodayOrFuture() {
        Fixture arrivalDay = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);
        service.changeConfirmedDates(arrivalDay.reservation().getId(),
                new ReservationDateChangeRequest(TODAY, TODAY.plusDays(3)));
        assertEquals(TODAY, arrivalDay.reservation().getCheckInDate());

        Fixture overdue = fixture(TODAY.minusDays(1), TODAY.plusDays(1), BookingSource.BOOKING_COM);
        service.changeConfirmedDates(overdue.reservation().getId(),
                new ReservationDateChangeRequest(TODAY, TODAY.plusDays(2)));
        assertEquals(TODAY, overdue.reservation().getCheckInDate());
    }

    /** Confirms a past arrival or non-positive interval is rejected before inventory or mutation. */
    @Test
    void shouldRejectInvalidChangedDatesWithoutMutation() {
        Fixture fixture = fixture(TODAY.minusDays(1), TODAY.plusDays(1), BookingSource.BOOKING_COM);

        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY.minusDays(1), TODAY.plusDays(2))),
                Reason.CHECK_IN_BEFORE_TODAY);
        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY, TODAY)),
                Reason.CHECK_OUT_NOT_AFTER_CHECK_IN);
        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY.plusDays(1), TODAY)),
                Reason.CHECK_OUT_NOT_AFTER_CHECK_IN);
        assertEquals(TODAY.minusDays(1), fixture.reservation().getCheckInDate());
        verify(audits, never()).save(any());
    }

    /** Confirms only the current Reservation is excluded from the shared half-open availability check. */
    @Test
    void shouldExplicitlyExcludeOnlyItselfFromAvailability() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);
        LocalDate out = TODAY.plusDays(3);

        service.changeConfirmedDates(fixture.reservation().getId(), new ReservationDateChangeRequest(TODAY, out));

        verify(availability).conflictedRoomIdsExcludingReservation(
                argThat(ids -> Set.copyOf(ids).equals(Set.of(fixture.roomA().getId(), fixture.roomB().getId()))),
                eq(TODAY), eq(out), eq(fixture.reservation().getId()));
        verify(availability, never()).conflictedRoomIds(any(), any(), any());
    }

    /** Confirms another Reservation or active Stay conflict causes no partial date/total mutation or audit. */
    @Test
    void shouldRejectOtherInventoryConflictAtomically() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);
        BigDecimal originalTotal = fixture.reservation().getTotalAmount();
        when(availability.conflictedRoomIdsExcludingReservation(any(), any(), any(), any()))
                .thenReturn(Set.of(fixture.roomA().getId()));

        assertReason(
                () -> service.changeConfirmedDates(fixture.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY, TODAY.plusDays(4))),
                Reason.ROOM_UNAVAILABLE);

        assertEquals(TODAY.plusDays(2), fixture.reservation().getCheckOutDate());
        assertEquals(0, originalTotal.compareTo(fixture.reservation().getTotalAmount()));
        verify(audits, never()).save(any());
    }

    /** Confirms shortening succeeds at the active-prepayment boundary and fails without mutation below it. */
    @Test
    void shouldEnforceActivePrepaymentAgainstTheProposedTotal() {
        Fixture acceptable = fixture(TODAY, TODAY.plusDays(4), BookingSource.BOOKING_COM);
        when(prepayments.activePaidTotal(acceptable.reservation().getId())).thenReturn(new BigDecimal("3500000"));
        service.changeConfirmedDates(acceptable.reservation().getId(),
                new ReservationDateChangeRequest(TODAY, TODAY.plusDays(2)));
        assertEquals(0, new BigDecimal("3500000").compareTo(acceptable.reservation().getTotalAmount()));

        Fixture excessive = fixture(TODAY, TODAY.plusDays(4), BookingSource.BOOKING_COM);
        BigDecimal original = excessive.reservation().getTotalAmount();
        when(prepayments.activePaidTotal(excessive.reservation().getId())).thenReturn(new BigDecimal("3500001"));
        assertReason(
                () -> service.changeConfirmedDates(excessive.reservation().getId(),
                        new ReservationDateChangeRequest(TODAY, TODAY.plusDays(2))),
                Reason.PREPAYMENT_EXCEEDS_TOTAL);
        assertEquals(TODAY.plusDays(4), excessive.reservation().getCheckOutDate());
        assertEquals(0, original.compareTo(excessive.reservation().getTotalAmount()));
        verify(audits, times(1)).save(any());
    }

    /** Confirms the lock order is Rooms (sorted), then Reservation, then mutable-state validation. */
    @Test
    void shouldLockRoomsThenReservationBeforeRevalidation() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);

        service.changeConfirmedDates(fixture.reservation().getId(),
                new ReservationDateChangeRequest(TODAY, TODAY.plusDays(3)));

        var order = inOrder(reservations, rooms, stays, availability, prepayments);
        order.verify(reservations).findRoomIdsByReservationId(fixture.reservation().getId());
        order.verify(rooms).lockAllByIdIn(any());
        order.verify(reservations).findByIdForUpdate(fixture.reservation().getId());
        order.verify(stays).existsByReservationId(fixture.reservation().getId());
        order.verify(availability).conflictedRoomIdsExcludingReservation(any(), any(), any(), any());
        order.verify(prepayments).activePaidTotal(fixture.reservation().getId());
    }

    /** Confirms a successful date change writes one reconstructable audit entry. */
    @Test
    void shouldAuditSuccessfulDateChangeWithOldAndNewDatesAndTotals() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);

        service.changeConfirmedDates(fixture.reservation().getId(),
                new ReservationDateChangeRequest(TODAY.plusDays(1), TODAY.plusDays(4)));

        AuditLog audit = capturedAudit();
        assertEquals(ReservationService.CHANGE_DATES_AUDIT_ACTION, field(audit, "action"));
        assertEquals("checkInDate=2026-09-24, checkOutDate=2026-09-26, totalAmount=3500000", field(audit, "oldValue"));
        assertEquals("checkInDate=2026-09-25, checkOutDate=2026-09-28, totalAmount=5250000", field(audit, "newValue"));
    }

    /** Confirms an OTA correction changes exactly that field and preserves verbatim non-blank reference semantics. */
    @Test
    void shouldCorrectOnlyOtaReferenceAndPreserveExistingWhitespaceSemantics() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.AIRBNB);
        UUID legacyExternalId = UUID.randomUUID();
        ReflectionTestUtils.setField(fixture.reservation(), "externalBookingId", legacyExternalId.toString());
        BigDecimal total = fixture.reservation().getTotalAmount();

        service.correctOtaBookingReference(
                fixture.reservation().getId(), new OtaReferenceCorrectionRequest("  AIR-NEW  "));

        assertEquals("  AIR-NEW  ", fixture.reservation().getOtaBookingReference());
        assertEquals(BookingSource.AIRBNB, fixture.reservation().getSource());
        assertEquals(legacyExternalId.toString(), ReflectionTestUtils.getField(fixture.reservation(), "externalBookingId"));
        assertEquals(TODAY, fixture.reservation().getCheckInDate());
        assertEquals(0, total.compareTo(fixture.reservation().getTotalAmount()));
        assertSame(fixture.roomA(), fixture.reservation().getRooms().get(0).getRoom());
        assertEquals("keep", fixture.reservation().getNotes());
    }

    /** Confirms DIRECT, wrong-state, blank and overlength OTA corrections are rejected and unaudited. */
    @Test
    void shouldRejectIneligibleOrInvalidOtaCorrections() {
        Fixture direct = fixture(TODAY, TODAY.plusDays(2), BookingSource.DIRECT);
        assertReason(
                () -> service.correctOtaBookingReference(direct.reservation().getId(),
                        new OtaReferenceCorrectionRequest("X")),
                Reason.DIRECT_RESERVATION);

        Fixture draft = fixture(TODAY, TODAY.plusDays(2), BookingSource.AGODA);
        ReflectionTestUtils.setField(draft.reservation(), "status", ReservationStatus.DRAFT);
        assertReason(
                () -> service.correctOtaBookingReference(draft.reservation().getId(),
                        new OtaReferenceCorrectionRequest("X")),
                Reason.RESERVATION_NOT_CONFIRMED);

        Fixture invalid = fixture(TODAY, TODAY.plusDays(2), BookingSource.AGODA);
        assertReason(
                () -> service.correctOtaBookingReference(invalid.reservation().getId(),
                        new OtaReferenceCorrectionRequest("   ")),
                Reason.OTA_REFERENCE_REQUIRED);
        assertReason(
                () -> service.correctOtaBookingReference(invalid.reservation().getId(),
                        new OtaReferenceCorrectionRequest("X".repeat(256))),
                Reason.OTA_REFERENCE_TOO_LONG);
        assertEquals("OTA-OLD", invalid.reservation().getOtaBookingReference());
        verify(audits, never()).save(any());
    }

    /** Confirms successful correction writes one old/new audit and failed correction writes none. */
    @Test
    void shouldAuditOnlySuccessfulOtaCorrection() {
        Fixture fixture = fixture(TODAY, TODAY.plusDays(2), BookingSource.BOOKING_COM);

        service.correctOtaBookingReference(
                fixture.reservation().getId(), new OtaReferenceCorrectionRequest("BK-NEW"));

        AuditLog audit = capturedAudit();
        assertEquals(ReservationService.CORRECT_OTA_REFERENCE_AUDIT_ACTION, field(audit, "action"));
        assertEquals("otaBookingReference=OTA-OLD", field(audit, "oldValue"));
        assertEquals("otaBookingReference=BK-NEW", field(audit, "newValue"));
        assertEquals(actor, field(audit, "userId"));
    }

    /** Confirms each request type exposes only fields belonging to its narrow operation. */
    @Test
    void shouldExposeOnlyNarrowRequestFields() {
        assertEquals(List.of("newCheckInDate", "newCheckOutDate"),
                java.util.Arrays.stream(ReservationDateChangeRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(List.of("otaBookingReference"),
                java.util.Arrays.stream(OtaReferenceCorrectionRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    private Fixture fixture(LocalDate in, LocalDate out, BookingSource source) {
        Guest guest = mock(Guest.class);
        Room roomA = Room.create(UUID.randomUUID(), "101", null, "1");
        Room roomB = Room.create(UUID.randomUUID(), "102", null, "1");
        Reservation reservation = new Reservation(
                UUID.randomUUID(), "R-1", guest, in, out, 1, 0, source,
                source == BookingSource.DIRECT ? null : "OTA-OLD", "VND", "keep");
        reservation.addRoom(new ReservationRoom(reservation, roomA, in, out, RATE_A));
        reservation.addRoom(new ReservationRoom(reservation, roomB, in, out, RATE_B));
        reservation.calculateTotal();
        reservation.confirm();
        List<UUID> ids = List.of(roomA.getId(), roomB.getId()).stream().sorted().toList();
        when(reservations.findRoomIdsByReservationId(reservation.getId())).thenReturn(ids);
        when(rooms.lockAllByIdIn(ids)).thenReturn(List.of(roomA, roomB));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        return new Fixture(reservation, roomA, roomB);
    }

    private void assertReason(Runnable operation, Reason expected) {
        ConfirmedReservationModificationException exception =
                assertThrows(ConfirmedReservationModificationException.class, operation::run);
        assertEquals(expected, exception.getModificationReason());
    }

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private Object field(AuditLog audit, String name) {
        return ReflectionTestUtils.getField(audit, name);
    }

    /** Test fixture containing one confirmed two-room Reservation. */
    private record Fixture(Reservation reservation, Room roomA, Room roomB) {}
}
