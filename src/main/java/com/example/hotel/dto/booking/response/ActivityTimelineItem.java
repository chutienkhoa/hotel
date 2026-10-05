package com.example.hotel.dto.booking.response;

import java.time.Instant;

/**
 * Supplies one presentation-ready Reservation Operational Timeline row for Reservation Detail.
 * {@code label} is already resolved in the active UI language; raw {@code AuditLog} values and
 * financial amounts are never included, so this record is always safe to render directly.
 *
 * @param occurredAt the instant the action was recorded
 * @param actorDisplay the username attributed to the action, or a safe fallback when unresolved
 * @param label the localized, human-readable description of what happened
 * @param tone the presentation tone of the entry's marker: {@code success}, {@code danger}, {@code warning} or
 *     {@code neutral}; derived from the stable action code only
 * @param reservationStatus the Reservation status name at the time of the entry, or {@code null} when not established
 */
public record ActivityTimelineItem(
        Instant occurredAt, String actorDisplay, String label, String tone, String reservationStatus) {

    /**
     * Creates a row with the given tone and no recorded status.
     *
     * @param occurredAt the instant the action was recorded
     * @param actorDisplay the username attributed to the action, or a safe fallback when unresolved
     * @param label the localized, human-readable description of what happened
     * @param tone the presentation tone of the entry's marker
     */
    public ActivityTimelineItem(Instant occurredAt, String actorDisplay, String label, String tone) {
        this(occurredAt, actorDisplay, label, tone, null);
    }

    /**
     * Creates a row with the neutral marker tone.
     *
     * @param occurredAt the instant the action was recorded
     * @param actorDisplay the username attributed to the action, or a safe fallback when unresolved
     * @param label the localized, human-readable description of what happened
     */
    public ActivityTimelineItem(Instant occurredAt, String actorDisplay, String label) {
        this(occurredAt, actorDisplay, label, "neutral", null);
    }
}
