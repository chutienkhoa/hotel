package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One structured Arrival Readiness finding. Carries no localized text.
 *
 * @param severity BLOCKER, WARNING or INFO
 * @param code stable issue code
 * @param roomNumber affected Room number for room issues, otherwise {@code null}
 * @param roomId affected Room identifier for room issues, otherwise {@code null}
 */
public record ArrivalReadinessIssue(ArrivalIssueSeverity severity, ArrivalIssueCode code, String roomNumber, UUID roomId) {

    /**
     * Creates an issue without a Room identifier.
     *
     * @param severity BLOCKER, WARNING or INFO
     * @param code stable issue code
     * @param roomNumber affected Room number, or {@code null}
     */
    public ArrivalReadinessIssue(ArrivalIssueSeverity severity, ArrivalIssueCode code, String roomNumber) {
        this(severity, code, roomNumber, null);
    }

    /**
     * Tells whether this is a BLOCKER caused by the state of an assigned Room, which a pre-check-in Room
     * reassignment can resolve.
     *
     * @return {@code true} for a room blocker
     */
    public boolean roomBlocker() {
        return severity == ArrivalIssueSeverity.BLOCKER && roomId != null;
    }
}
