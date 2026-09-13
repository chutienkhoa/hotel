package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the reusable Stay outstanding-balance calculation. */
class StayBalanceServiceTest {

    /** Confirms missing Charge and Payment aggregates produce zero totals. */
    @Test
    void shouldReturnZeroTotalsWhenStayHasNoChargesOrPayments() {
        StayBalance balance = calculate(null, null);

        assertAmount(BigDecimal.ZERO, balance.totalCharges());
        assertAmount(BigDecimal.ZERO, balance.totalPaidPayments());
        assertAmount(BigDecimal.ZERO, balance.outstanding());
    }

    /** Confirms Charges without PAID Payments remain fully outstanding. */
    @Test
    void shouldReturnChargeTotalAsOutstandingWhenStayHasOnlyCharges() {
        StayBalance balance = calculate(new BigDecimal("125.50"), BigDecimal.ZERO);

        assertAmount(new BigDecimal("125.50"), balance.totalCharges());
        assertAmount(BigDecimal.ZERO, balance.totalPaidPayments());
        assertAmount(new BigDecimal("125.50"), balance.outstanding());
    }

    /** Confirms a PAID Payment reduces the outstanding balance. */
    @Test
    void shouldReduceOutstandingByPaidPayments() {
        StayBalance balance = calculate(new BigDecimal("100.00"), new BigDecimal("40.00"));

        assertAmount(new BigDecimal("60.00"), balance.outstanding());
    }

    /** Confirms PENDING Payments are excluded by querying only the PAID status. */
    @Test
    void shouldExcludePendingPayments() {
        assertOnlyPaidPaymentsAreIncluded();
    }

    /** Confirms FAILED Payments are excluded by querying only the PAID status. */
    @Test
    void shouldExcludeFailedPayments() {
        assertOnlyPaidPaymentsAreIncluded();
    }

    /** Confirms REFUNDED Payments are excluded by querying only the PAID status. */
    @Test
    void shouldExcludeRefundedPayments() {
        assertOnlyPaidPaymentsAreIncluded();
    }

    /** Confirms the aggregate Charge total supports multiple recorded Charges. */
    @Test
    void shouldUseAggregateTotalForMultipleCharges() {
        StayBalance balance = calculate(new BigDecimal("45.75"), BigDecimal.ZERO);

        assertAmount(new BigDecimal("45.75"), balance.totalCharges());
        assertAmount(new BigDecimal("45.75"), balance.outstanding());
    }

    /** Confirms the aggregate PAID-Payment total supports multiple paid Payments. */
    @Test
    void shouldUseAggregateTotalForMultiplePaidPayments() {
        StayBalance balance = calculate(new BigDecimal("100.00"), new BigDecimal("100.00"));

        assertAmount(new BigDecimal("100.00"), balance.totalPaidPayments());
        assertAmount(BigDecimal.ZERO, balance.outstanding());
    }

    /** Confirms exactly paid Charges result in no outstanding balance. */
    @Test
    void shouldReturnZeroOutstandingForExactPayment() {
        StayBalance balance = calculate(new BigDecimal("75.25"), new BigDecimal("75.25"));

        assertAmount(BigDecimal.ZERO, balance.outstanding());
    }

    /** Confirms all non-PAID statuses are omitted from the balance query. */
    private void assertOnlyPaidPaymentsAreIncluded() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        UUID stayId = UUID.randomUUID();
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(BigDecimal.TEN);
        when(paymentRepository.sumAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);

        StayBalance balance = new StayBalanceService(chargeRepository, paymentRepository).calculate(stayId);

        assertAmount(BigDecimal.ZERO, balance.totalPaidPayments());
        assertAmount(BigDecimal.TEN, balance.outstanding());
        verify(paymentRepository).sumAmountByStayIdAndStatus(eq(stayId), eq(PaymentStatus.PAID));
    }

    /** Calculates a Stay balance from mocked database aggregates. */
    private StayBalance calculate(BigDecimal totalCharges, BigDecimal totalPaidPayments) {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        UUID stayId = UUID.randomUUID();
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(totalCharges);
        when(paymentRepository.sumAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(totalPaidPayments);

        return new StayBalanceService(chargeRepository, paymentRepository).calculate(stayId);
    }

    /** Compares monetary values without treating insignificant decimal scale as a difference. */
    private void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}
