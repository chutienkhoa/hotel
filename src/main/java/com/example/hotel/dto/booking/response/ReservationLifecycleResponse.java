package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.util.List;

/**
 * Presents when and by whom each lifecycle transition of a Reservation happened, read from its existing audit history.
 * Only the stable action code, the time, and the actor's display name are used: raw audit old/new values are never read.
 * A transition that left no audit row (for example a legacy Reservation) is {@code null}.
 *
 * @param confirmed the CONFIRM event, or {@code null}
 * @param checkedIn the CHECK_IN event, or {@code null}
 * @param checkedOut the CHECK_OUT event, or {@code null}
 * @param cancelled the CANCEL event, or {@code null}
 * @param noShow the NO_SHOW event, or {@code null}
 */
public record ReservationLifecycleResponse(
        Event confirmed, Event checkedIn, Event checkedOut, Event cancelled, Event noShow) {

    /**
     * One lifecycle transition.
     *
     * @param occurredAt the instant the transition was audited
     * @param actorDisplay the acting user's display name
     */
    public record Event(Instant occurredAt, String actorDisplay) {}

    /**
     * Derives the lifecycle events from a Reservation's audit entries. When an action appears more than once the
     * latest entry wins.
     *
     * @param entries the Reservation's audit entries, in any order
     * @return the lifecycle events; never {@code null}
     */
    public static ReservationLifecycleResponse from(List<ReservationActivityEntry> entries) {
        return new ReservationLifecycleResponse(
                latest(entries, "CONFIRM"),
                latest(entries, "CHECK_IN"),
                latest(entries, "CHECK_OUT"),
                latest(entries, "CANCEL"),
                latest(entries, "NO_SHOW"));
    }

    private static Event latest(List<ReservationActivityEntry> entries, String action) {
        ReservationActivityEntry latest = null;
        for (ReservationActivityEntry entry : entries) {
            if (action.equals(entry.action())
                    && (latest == null || !entry.occurredAt().isBefore(latest.occurredAt()))) {
                latest = entry;
            }
        }
        return latest == null ? null : new Event(latest.occurredAt(), latest.actorDisplay());
    }
}
