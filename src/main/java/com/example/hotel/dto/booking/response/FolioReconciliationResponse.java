package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only result of reconciling one Stay's folio ROOM and guest-service charges with their revenue sources.
 * Diagnostic only: it never changes data and never blocks check-out.
 *
 * @param stayId Stay identifier
 * @param status {@code MATCHED} or {@code MISMATCH}
 * @param expectedOriginalRoomCharges sum of the booked ReservationRoom totals
 * @param expectedExtensionRoomCharges sum of the extension line amounts
 * @param expectedAccommodationCharges original plus extension
 * @param actualOriginalRoomCharges sum of ROOM charges linked to a ReservationRoom
 * @param actualExtensionRoomCharges sum of the charges linked from extension lines
 * @param actualAccommodationCharges original plus extension
 * @param issues every detected mismatch
 */
public record FolioReconciliationResponse(
        UUID stayId,
        String status,
        BigDecimal expectedOriginalRoomCharges,
        BigDecimal expectedExtensionRoomCharges,
        BigDecimal expectedAccommodationCharges,
        BigDecimal actualOriginalRoomCharges,
        BigDecimal actualExtensionRoomCharges,
        BigDecimal actualAccommodationCharges,
        List<Issue> issues) {

    /** Kinds of mismatch. */
    public enum IssueType {
        MISSING_ORIGINAL_ROOM_CHARGE,
        ORIGINAL_ROOM_CHARGE_AMOUNT_MISMATCH,
        MISSING_EXTENSION_CHARGE,
        EXTENSION_CHARGE_AMOUNT_MISMATCH,
        ORPHAN_ROOM_CHARGE,
        SERVICE_CHARGE_WITHOUT_REVENUE,
        SERVICE_REVENUE_AMOUNT_MISMATCH
    }

    /**
     * One mismatch.
     *
     * @param type kind of mismatch
     * @param subjectId the Charge, ReservationRoom or extension line concerned
     * @param expected expected amount, or {@code null}
     * @param actual actual amount, or {@code null}
     */
    public record Issue(IssueType type, UUID subjectId, BigDecimal expected, BigDecimal actual) {}
}
