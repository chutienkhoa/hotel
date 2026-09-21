package com.example.hotel.dto.booking.response;

/**
 * One structured Arrival Readiness finding. Carries no localized text.
 *
 * @param severity BLOCKER, WARNING or INFO
 * @param code stable issue code
 * @param roomNumber affected Room number for room issues, otherwise {@code null}
 */
public record ArrivalReadinessIssue(ArrivalIssueSeverity severity, ArrivalIssueCode code, String roomNumber) {}
