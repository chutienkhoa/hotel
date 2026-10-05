package com.example.hotel.dto.booking.response;

import java.time.Instant;

/**
 * Supplies one raw Reservation Operational Timeline entry: the stable audited action identifier,
 * never a translated label, and the resolved actor display name. Carries no financial or other
 * sensitive detail from the underlying audit row.
 *
 * @param occurredAt the instant the action was recorded
 * @param actorDisplay the username attributed to the action, or a safe fallback when unresolved
 * @param action the stable AuditLog action identifier, e.g. {@code CHECK_IN}
 * @param reservationStatus the Reservation status name at the time of this entry (the status a lifecycle action
 *     produced, or the status in force when any other action was recorded), or {@code null} when the audit trail
 *     does not establish it
 */
public record ReservationActivityEntry(
        Instant occurredAt, String actorDisplay, String action, String reservationStatus) {

    /**
     * Creates an entry whose status at the time is not established.
     *
     * @param occurredAt the instant the action was recorded
     * @param actorDisplay the username attributed to the action
     * @param action the stable AuditLog action identifier
     */
    public ReservationActivityEntry(Instant occurredAt, String actorDisplay, String action) {
        this(occurredAt, actorDisplay, action, null);
    }
}
