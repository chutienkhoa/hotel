package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.exception.LocalizedResponseStatusException;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Cancellation Reason and No-show Guard/Reason V1 business rules in isolation from Postgres. */
class ReservationCancellationAndNoShowServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final PrepaymentService prepayments = mock(PrepaymentService.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final ReservationService service = new ReservationService(
            reservations,
            mock(GuestRepository.class),
            mock(RoomRepository.class),
            mock(StayRepository.class),
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            audits,
            new ReservationMapper(),
            mock(ReservationNumberGenerator.class),
            mock(StayBalanceService.class),
            mock(RoomAvailabilityService.class),
            prepayments,
            Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final UUID actor = UUID.randomUUID();

    /** Authenticates a staff actor for every test. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "manager"), null, List.of()));
    }

    /** Clears thread-local authentication. */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms cancellation persists the reason and audits only the state transition, not the reason. */
    @Test
    void cancelPersistsReasonAndAuditsOnlyTheTransition() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.GUEST_REQUEST, null));

        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertEquals(CancellationReasonCode.GUEST_REQUEST, reservation.getCancellationReasonCode());
        assertNull(reservation.getCancellationReasonDetail());
        AuditLog audit = capturedAudit();
        assertEquals("CANCEL", field(audit, "action"));
        assertEquals("CONFIRMED", field(audit, "oldValue"));
        assertEquals("CANCELLED", field(audit, "newValue"));
    }

    /** Confirms the optional detail is trimmed and persisted. */
    @Test
    void cancelPersistsTrimmedOptionalDetail() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.PAYMENT_ISSUE, "  Card declined twice  "));

        assertEquals("Card declined twice", reservation.getCancellationReasonDetail());
    }

    /** Confirms OTHER with non-blank detail is allowed. */
    @Test
    void cancelAllowsOtherWithDetail() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.OTHER, "Explained by phone"));

        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertEquals("Explained by phone", reservation.getCancellationReasonDetail());
    }

    /** Confirms OTHER with blank detail is rejected before any mutation or audit. */
    @Test
    void cancelRejectsOtherWithBlankDetail() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.cancel(reservation.getId(),
                        new CancelReservationRequest(CancellationReasonCode.OTHER, "   ")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("reservation.cancel.error.detailRequiredForOther", exception.getMessageKey());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms a missing reason code is rejected before any mutation or audit. */
    @Test
    void cancelRejectsMissingReasonCode() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.cancel(reservation.getId(), new CancelReservationRequest(null, null)));

        assertEquals("reservation.cancel.error.reasonRequired", exception.getMessageKey());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms a DRAFT (abandoned/invalid draft) can be cancelled, persisting the reason and auditing DRAFT to CANCELLED. */
    @Test
    void cancelFromDraftPersistsReasonAndAuditsTheTransition() {
        Reservation reservation = draft(TODAY, TODAY.plusDays(2));

        service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.CHANGE_OF_PLANS, "  Guest never called back  "));

        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertEquals(CancellationReasonCode.CHANGE_OF_PLANS, reservation.getCancellationReasonCode());
        assertEquals("Guest never called back", reservation.getCancellationReasonDetail());
        AuditLog audit = capturedAudit();
        assertEquals("CANCEL", field(audit, "action"));
        assertEquals("DRAFT", field(audit, "oldValue"));
        assertEquals("CANCELLED", field(audit, "newValue"));
    }

    /** Confirms cancelling a DRAFT still requires a reason code, before any mutation or audit. */
    @Test
    void cancelFromDraftRejectsMissingReasonCode() {
        Reservation reservation = draft(TODAY, TODAY.plusDays(2));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.cancel(reservation.getId(), new CancelReservationRequest(null, null)));

        assertEquals("reservation.cancel.error.reasonRequired", exception.getMessageKey());
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms cancelling a DRAFT with reason OTHER still requires a non-blank detail. */
    @Test
    void cancelFromDraftRejectsOtherWithBlankDetail() {
        Reservation reservation = draft(TODAY, TODAY.plusDays(2));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.cancel(reservation.getId(),
                        new CancelReservationRequest(CancellationReasonCode.OTHER, "  ")));

        assertEquals("reservation.cancel.error.detailRequiredForOther", exception.getMessageKey());
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms a cancelled DRAFT is terminal: it cannot be cancelled again or confirmed afterwards. */
    @Test
    void cancelledDraftIsTerminal() {
        Reservation reservation = draft(TODAY, TODAY.plusDays(2));
        service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.DUPLICATE_BOOKING, null));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.cancel(reservation.getId(),
                        new CancelReservationRequest(CancellationReasonCode.DUPLICATE_BOOKING, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertThrows(IllegalStateException.class, reservation::confirm);
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
    }

    /** Confirms every starting status other than DRAFT and CONFIRMED is still rejected with the existing 409. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT"})
    void cancelRejectsInvalidStartingStatuses(ReservationStatus status) {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));
        ReflectionTestUtils.setField(reservation, "status", status);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.cancel(reservation.getId(),
                        new CancelReservationRequest(CancellationReasonCode.GUEST_REQUEST, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(status, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms an active prepayment still blocks cancellation, unchanged from prior behavior. */
    @Test
    void cancelIsStillBlockedByActivePrepayment() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));
        doThrow(new LocalizedResponseStatusException(
                        HttpStatus.CONFLICT, "payment.prepayment.error.blocksCancel", "blocked"))
                .when(prepayments)
                .requireNoActivePrepayments(eq(reservation.getId()), any(), any());

        assertThrows(ResponseStatusException.class, () -> service.cancel(reservation.getId(),
                new CancelReservationRequest(CancellationReasonCode.GUEST_REQUEST, null)));

        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------------------
    // No-show
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms no-show persists the reason and audits only the state transition, not the reason. */
    @Test
    void noShowPersistsReasonAndAuditsOnlyTheTransition() {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));

        service.noShow(reservation.getId(),
                new NoShowReservationRequest("Guest did not arrive and could not be contacted."));

        assertEquals(ReservationStatus.NO_SHOW, reservation.getStatus());
        assertEquals("Guest did not arrive and could not be contacted.", reservation.getNoShowReason());
        AuditLog audit = capturedAudit();
        assertEquals("NO_SHOW", field(audit, "action"));
        assertEquals("CONFIRMED", field(audit, "oldValue"));
        assertEquals("NO_SHOW", field(audit, "newValue"));
    }

    /** Confirms the reason is trimmed before persistence. */
    @Test
    void noShowTrimsReason() {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));

        service.noShow(reservation.getId(), new NoShowReservationRequest("  never showed up  "));

        assertEquals("never showed up", reservation.getNoShowReason());
    }

    /** Confirms a blank-only reason is rejected before any mutation or audit. */
    @Test
    void noShowRejectsBlankReason() {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.noShow(reservation.getId(), new NoShowReservationRequest("   ")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("reservation.noShow.error.reasonRequired", exception.getMessageKey());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms every non-CONFIRMED starting status is still rejected exactly as before this feature. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT"})
    void noShowRejectsInvalidStartingStatuses(ReservationStatus status) {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));
        ReflectionTestUtils.setField(reservation, "status", status);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.noShow(reservation.getId(), new NoShowReservationRequest("reason")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(status, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms a same-day arrival cannot be marked NO_SHOW: the core new temporal guard. */
    @Test
    void noShowRejectsSameDayCheckIn() {
        Reservation reservation = confirmed(TODAY, TODAY.plusDays(2));

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.noShow(reservation.getId(), new NoShowReservationRequest("reason")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("reservation.noShow.error.notEligible", exception.getMessageKey());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    /** Confirms a future arrival cannot be marked NO_SHOW. */
    @Test
    void noShowRejectsFutureCheckIn() {
        Reservation reservation = confirmed(TODAY.plusDays(3), TODAY.plusDays(5));

        assertThrows(LocalizedResponseStatusException.class,
                () -> service.noShow(reservation.getId(), new NoShowReservationRequest("reason")));

        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    /** Confirms an overdue arrival (checkInDate strictly before hotelToday) is eligible for NO_SHOW. */
    @Test
    void noShowAllowsCheckInDateBeforeToday() {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));

        service.noShow(reservation.getId(), new NoShowReservationRequest("reason"));

        assertEquals(ReservationStatus.NO_SHOW, reservation.getStatus());
    }

    /** Confirms the boundary is driven by the injected hotel Clock, not system time: exactly TODAY is rejected,
     * exactly TODAY.minusDays(1) is accepted. */
    @Test
    void noShowUsesInjectedClockDeterministicallyAtTheBoundary() {
        Reservation todayArrival = confirmed(TODAY, TODAY.plusDays(2));
        assertThrows(LocalizedResponseStatusException.class,
                () -> service.noShow(todayArrival.getId(), new NoShowReservationRequest("reason")));

        Reservation yesterdayArrival = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));
        service.noShow(yesterdayArrival.getId(), new NoShowReservationRequest("reason"));
        assertEquals(ReservationStatus.NO_SHOW, yesterdayArrival.getStatus());
    }

    /** Confirms an active prepayment still blocks no-show, unchanged from prior behavior. */
    @Test
    void noShowIsStillBlockedByActivePrepayment() {
        Reservation reservation = confirmed(TODAY.minusDays(1), TODAY.plusDays(1));
        doThrow(new LocalizedResponseStatusException(
                        HttpStatus.CONFLICT, "payment.prepayment.error.blocksNoShow", "blocked"))
                .when(prepayments)
                .requireNoActivePrepayments(eq(reservation.getId()), any(), any());

        assertThrows(ResponseStatusException.class,
                () -> service.noShow(reservation.getId(), new NoShowReservationRequest("reason")));

        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        verify(audits, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Historical rows / mapping
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms a historical terminal Reservation with no recorded reason (pre-migration row) maps cleanly. */
    @Test
    void mapperHandlesHistoricalNullReasonsGracefully() {
        Reservation reservation = confirmed(TODAY.minusDays(10), TODAY.minusDays(8));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CANCELLED);

        ReservationDetailResponse response =
                new ReservationMapper().toDetailResponse(reservation, List.of(), null, null, null, false, null);

        assertEquals("CANCELLED", response.status());
        assertNull(response.cancellationReasonCode());
        assertNull(response.cancellationReasonDetail());
        assertNull(response.noShowReason());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private Reservation draft(LocalDate checkIn, LocalDate checkOut) {
        Guest guest = mock(Guest.class);
        Reservation reservation =
                new Reservation(UUID.randomUUID(), "R-1", guest, checkIn, checkOut, "VND", null);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        return reservation;
    }

    private Reservation confirmed(LocalDate checkIn, LocalDate checkOut) {
        Guest guest = mock(Guest.class);
        Reservation reservation =
                new Reservation(UUID.randomUUID(), "R-1", guest, checkIn, checkOut, "VND", null);
        reservation.confirm();
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        return reservation;
    }

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private Object field(AuditLog audit, String name) {
        return ReflectionTestUtils.getField(audit, name);
    }
}
