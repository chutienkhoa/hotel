package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Proves the settlement outcome of a cross-currency Payment end to end: what
 * {@code PaymentService} records, what {@code StayBalanceService} then computes, and what
 * {@code DepartureReadinessRules} therefore decides about check-out.
 *
 * <p>Before Payment {@code appliedAmount} was normalized into the Reservation currency, a
 * 999,999 VND tender at "1 USD = 25,000 VND" settled a 40.00 USD Folio as 39.999960 USD, leaving
 * 0.000040 USD outstanding. Check-out requires Outstanding to be exactly zero, so that economically
 * meaningless remainder blocked a guest who had actually paid, with no way to clear it. These tests
 * pin the fix at the point where it matters: the balance, not a check-out tolerance.</p>
 */
class CrossCurrencySettlementTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal RATE = new BigDecimal("25000");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final UUID stayId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "cashier"), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The audit's dust scenario. A 40.00 USD Folio is settled with 999,999 VND at 25,000: the raw
     * conversion is 39.99996 USD, the recorded applied amount is 40.00 USD, Outstanding is exactly
     * zero and the Stay is READY to check out.
     */
    @Test
    void shouldSettleUsdFolioWithVndTenderLeavingNoDust() {
        BigDecimal totalCharges = new BigDecimal("40.00");
        PaymentResponse payment = recordPaid(
                "USD", totalCharges, new BigDecimal("999999"), PaymentCurrency.VND, RATE);

        assertEquals(new BigDecimal("40.00"), payment.appliedAmount());

        StayBalance balance = balanceAfter(totalCharges, payment.appliedAmount());

        assertEquals(0, balance.outstanding().compareTo(BigDecimal.ZERO));
        assertEquals(
                DepartureReadinessRules.FinancialReadiness.READY,
                DepartureReadinessRules.readiness(balance.outstanding()));
    }

    /** The mirrored direction: a 1,000,000 VND Folio settled by a 40 USD tender reaches zero too. */
    @Test
    void shouldSettleVndFolioWithUsdTenderLeavingNoDust() {
        BigDecimal totalCharges = new BigDecimal("1000000");
        PaymentResponse payment = recordPaid(
                "VND", totalCharges, new BigDecimal("40"), PaymentCurrency.USD, RATE);

        assertEquals(new BigDecimal("1000000"), payment.appliedAmount());

        StayBalance balance = balanceAfter(totalCharges, payment.appliedAmount());

        assertEquals(0, balance.outstanding().compareTo(BigDecimal.ZERO));
        assertEquals(
                DepartureReadinessRules.FinancialReadiness.READY,
                DepartureReadinessRules.readiness(balance.outstanding()));
    }

    /** Confirms a genuine shortfall still blocks check-out: normalization must not settle it away. */
    @Test
    void shouldStillRequirePaymentWhenTheGuestActuallyUnderpays() {
        BigDecimal totalCharges = new BigDecimal("40.00");
        PaymentResponse payment = recordPaid(
                "USD", totalCharges, new BigDecimal("875000"), PaymentCurrency.VND, RATE);

        assertEquals(new BigDecimal("35.00"), payment.appliedAmount());

        StayBalance balance = balanceAfter(totalCharges, payment.appliedAmount());

        assertEquals(0, new BigDecimal("5.00").compareTo(balance.outstanding()));
        assertEquals(
                DepartureReadinessRules.FinancialReadiness.PAYMENT_REQUIRED,
                DepartureReadinessRules.readiness(balance.outstanding()));
    }

    /**
     * Records a paid cross-currency Payment against a checked-in Stay.
     *
     * @param reservationCurrency owning Reservation currency
     * @param totalCharges current ACTIVE Charge total of the Stay
     * @param amount tender amount
     * @param tender tender currency
     * @param exchangeRate "1 USD = rate VND"
     * @return the recorded Payment
     */
    private PaymentResponse recordPaid(
            String reservationCurrency,
            BigDecimal totalCharges,
            BigDecimal amount,
            PaymentCurrency tender,
            BigDecimal exchangeRate) {
        Stay stay = mock(Stay.class);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(UUID.randomUUID());
        when(reservation.getCurrency()).thenReturn(reservationCurrency);
        when(stay.getId()).thenReturn(stayId);
        when(stay.getStatus()).thenReturn(StayStatus.CHECKED_IN);
        when(stay.getReservation()).thenReturn(reservation);
        when(stays.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(charges.sumAmountByStayId(stayId)).thenReturn(totalCharges);
        when(payments.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID)).thenReturn(BigDecimal.ZERO);
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentService service = new PaymentService(
                payments,
                stays,
                charges,
                new PaymentMapper(),
                mock(AuditLogRepository.class),
                Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZONE),
                mock(com.example.hotel.repository.common.AppUserRepository.class));
        return service.recordPaid(
                stayId,
                new PaymentCreateRequest(amount, tender, exchangeRate, PaymentMethod.CASH, null));
    }

    /**
     * Computes the Stay balance the Folio and check-out would see once the Payment is PAID.
     *
     * @param totalCharges ACTIVE Charge total
     * @param totalApplied PAID Payment applied total
     * @return the calculated balance
     */
    private StayBalance balanceAfter(BigDecimal totalCharges, BigDecimal totalApplied) {
        ChargeRepository chargeTotals = mock(ChargeRepository.class);
        PaymentRepository paymentTotals = mock(PaymentRepository.class);
        when(chargeTotals.sumAmountByStayId(stayId)).thenReturn(totalCharges);
        when(paymentTotals.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID)).thenReturn(totalApplied);
        return new StayBalanceService(chargeTotals, paymentTotals).calculate(stayId);
    }
}
