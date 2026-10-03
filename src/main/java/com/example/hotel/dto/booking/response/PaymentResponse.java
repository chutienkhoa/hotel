package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Exposes Payment data without client-controllable audit fields.
 *
 * @param addedByUsername the username of the staff member who recorded the Payment, resolved from the audit
 *     {@code createdBy} identifier, or {@code null} when not resolved by the caller
 */
public record PaymentResponse(
        UUID id,
        UUID stayId,
        BigDecimal amount,
        String currency,
        BigDecimal exchangeRate,
        BigDecimal appliedAmount,
        String method,
        String status,
        Instant paidAt,
        String reference,
        String refundReason,
        String voidReason,
        String addedByUsername) {

    /**
     * Creates a Payment response without the resolved "added by" username, for existing fixtures/tests that
     * predate this presentation field. Production mapping always uses the canonical constructor.
     *
     * @param id the Payment identifier
     * @param stayId the owning Stay identifier, or {@code null} for a prepayment not yet linked to a Stay
     * @param amount the tendered Payment amount
     * @param currency the tendered Payment currency
     * @param exchangeRate the optional cross-currency exchange rate
     * @param appliedAmount the amount applied to the Folio in Reservation currency
     * @param method the Payment method
     * @param status the current Payment status
     * @param paidAt the instant the Payment was recorded as paid, or {@code null}
     * @param reference the optional external reference
     * @param refundReason the optional refund reason
     * @param voidReason the optional void reason
     */
    public PaymentResponse(
            UUID id,
            UUID stayId,
            BigDecimal amount,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal appliedAmount,
            String method,
            String status,
            Instant paidAt,
            String reference,
            String refundReason,
            String voidReason) {
        this(id, stayId, amount, currency, exchangeRate, appliedAmount, method, status, paidAt, reference,
                refundReason, voidReason, null);
    }
}
