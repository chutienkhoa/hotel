package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplies the read-only financial context of the Check-out Review, shown only to a viewer who may read detailed
 * financial data ({@code MANAGE_PAYMENT}). Every total comes from the authoritative {@code StayBalanceService}; the
 * rows are the Stay's Charges and Payments exactly as the Folio lists them.
 *
 * @param totalCharges sum of ACTIVE Charge amounts, in the Reservation currency
 * @param totalPaidPayments sum of PAID Payment applied amounts, in the Reservation currency
 * @param outstanding outstanding balance; checkout needs exactly zero
 * @param currency the Reservation currency
 * @param charges every Charge of the Stay (VOIDED ones carry their status and are not part of the total)
 * @param payments every Payment of the Stay (only PAID ones are part of the total)
 * @param currentRoomRates nightly rate of each current Room (booked price snapshot), keyed by Room identifier
 */
public record CheckOutFinancialSummary(
        BigDecimal totalCharges,
        BigDecimal totalPaidPayments,
        BigDecimal outstanding,
        String currency,
        List<ChargeResponse> charges,
        List<PaymentResponse> payments,
        java.util.Map<java.util.UUID, BigDecimal> currentRoomRates) {

    /**
     * Creates a summary without room rates.
     *
     * @param totalCharges ACTIVE Charge total
     * @param totalPaidPayments PAID Payment total
     * @param outstanding outstanding balance
     * @param currency Reservation currency
     * @param charges Stay Charges
     * @param payments Stay Payments
     */
    public CheckOutFinancialSummary(
            BigDecimal totalCharges, BigDecimal totalPaidPayments, BigDecimal outstanding, String currency,
            List<ChargeResponse> charges, List<PaymentResponse> payments) {
        this(totalCharges, totalPaidPayments, outstanding, currency, charges, payments, java.util.Map.of());
    }
}
