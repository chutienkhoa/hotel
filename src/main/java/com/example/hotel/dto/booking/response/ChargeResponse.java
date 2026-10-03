package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Exposes a recorded Charge without client-controllable audit fields.
 *
 * @param addedByUsername the username of the staff member who recorded the Charge, resolved from the audit
 *     {@code createdBy} identifier, or {@code null} when not resolved by the caller
 */
public record ChargeResponse(
        UUID id,
        UUID stayId,
        String type,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        Instant chargedAt,
        String status,
        String voidReason,
        String addedByUsername) {

    /**
     * Creates a Charge response without the resolved "added by" username, for existing fixtures/tests that
     * predate this presentation field. Production mapping always uses the canonical constructor.
     *
     * @param id the Charge identifier
     * @param stayId the owning Stay identifier
     * @param type the Charge type
     * @param description the optional Charge description
     * @param quantity the optional itemized quantity
     * @param unitPrice the optional itemized unit price
     * @param amount the Charge amount
     * @param chargedAt the instant the Charge was recorded
     * @param status the current Charge status
     * @param voidReason the optional void reason
     */
    public ChargeResponse(
            UUID id,
            UUID stayId,
            String type,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            Instant chargedAt,
            String status,
            String voidReason) {
        this(id, stayId, type, description, quantity, unitPrice, amount, chargedAt, status, voidReason, null);
    }
}
