package com.example.hotel.service.booking;

import java.math.BigDecimal;

/**
 * The single V1 rule for a Stay's outstanding balance and financial departure readiness, shared by
 * {@link StayBalanceService}, the Check-out list and review, and the Front Desk worklist so they cannot silently
 * disagree. Outstanding is total Charges minus PAID applied Payment amounts; readiness is READY only when it is
 * exactly zero. Actual check-out remains authoritative and may add state-consistency validation.
 */
public final class DepartureReadinessRules {

    /** Financial departure readiness. Derived, never persisted. */
    public enum FinancialReadiness {
        /** Outstanding balance is zero. */
        READY,
        /** Outstanding balance is not zero. */
        PAYMENT_REQUIRED
    }

    private DepartureReadinessRules() {}

    /**
     * Computes the outstanding balance.
     *
     * @param totalCharges sum of Charge amounts
     * @param totalPaid sum of PAID applied Payment amounts
     * @return charges minus paid
     */
    public static BigDecimal outstanding(BigDecimal totalCharges, BigDecimal totalPaid) {
        return totalCharges.subtract(totalPaid);
    }

    /**
     * Classifies an outstanding balance.
     *
     * @param outstanding outstanding balance
     * @return READY when zero, otherwise PAYMENT_REQUIRED
     */
    public static FinancialReadiness readiness(BigDecimal outstanding) {
        return outstanding.compareTo(BigDecimal.ZERO) == 0
                ? FinancialReadiness.READY
                : FinancialReadiness.PAYMENT_REQUIRED;
    }
}
