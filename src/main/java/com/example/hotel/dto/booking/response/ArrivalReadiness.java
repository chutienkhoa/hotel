package com.example.hotel.dto.booking.response;

import java.util.List;

/**
 * Derived, non-persisted Arrival Readiness of a Reservation: NEEDS_ATTENTION when any issue is a BLOCKER,
 * otherwise READY. Warnings and info never change the overall state.
 *
 * @param state overall readiness
 * @param timing Early/Normal/Late classification against the hotel current date
 * @param issues findings ordered BLOCKER, WARNING, INFO
 */
public record ArrivalReadiness(ArrivalReadinessState state, CheckInTiming timing, List<ArrivalReadinessIssue> issues) {

    /**
     * Returns the BLOCKER issues.
     *
     * @return blockers only
     */
    public List<ArrivalReadinessIssue> blockers() {
        return issues.stream().filter(issue -> issue.severity() == ArrivalIssueSeverity.BLOCKER).toList();
    }
}
