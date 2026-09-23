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
 */
public record ActivityTimelineItem(Instant occurredAt, String actorDisplay, String label) {}
