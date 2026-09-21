package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One structured Arrival Readiness finding. Carries no localized text; the presentation layer localizes it from the
 * code and {@link #messageArguments()}.
 *
 * @param severity BLOCKER, WARNING or INFO
 * @param code stable issue code
 * @param roomNumber affected Room number for room issues, otherwise {@code null}
 * @param roomId affected Room identifier for room issues, otherwise {@code null}
 * @param adultCount adults, for adult-capacity issues, otherwise {@code null}
 * @param totalAdultCapacity summed adult capacity, for {@code INSUFFICIENT_ADULT_CAPACITY}, otherwise {@code null}
 * @param roomTypeName RoomType without configured capacity, for {@code CAPACITY_NOT_CONFIGURED}, otherwise {@code null}
 */
public record ArrivalReadinessIssue(
        ArrivalIssueSeverity severity,
        ArrivalIssueCode code,
        String roomNumber,
        UUID roomId,
        Integer adultCount,
        Integer totalAdultCapacity,
        String roomTypeName) {

    /**
     * Creates an issue without capacity details.
     *
     * @param severity BLOCKER, WARNING or INFO
     * @param code stable issue code
     * @param roomNumber affected Room number, or {@code null}
     * @param roomId affected Room identifier, or {@code null}
     */
    public ArrivalReadinessIssue(
            ArrivalIssueSeverity severity, ArrivalIssueCode code, String roomNumber, UUID roomId) {
        this(severity, code, roomNumber, roomId, null, null, null);
    }

    /**
     * Creates an issue without a Room identifier.
     *
     * @param severity BLOCKER, WARNING or INFO
     * @param code stable issue code
     * @param roomNumber affected Room number, or {@code null}
     */
    public ArrivalReadinessIssue(ArrivalIssueSeverity severity, ArrivalIssueCode code, String roomNumber) {
        this(severity, code, roomNumber, null, null, null, null);
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

    /**
     * Returns the message arguments for this issue's localized text: {@code {0}} is the room number for room issues,
     * the adult count and capacity for {@code INSUFFICIENT_ADULT_CAPACITY} ({0}, {1}), or the RoomType name for
     * {@code CAPACITY_NOT_CONFIGURED} ({0}).
     *
     * @return the ordered message arguments
     */
    Object[] messageArguments() {
        return switch (code) {
            case INSUFFICIENT_ADULT_CAPACITY -> new Object[] {adultCount, totalAdultCapacity};
            case CAPACITY_NOT_CONFIGURED -> new Object[] {roomTypeName == null ? "?" : roomTypeName};
            default -> new Object[] {roomNumber == null ? "" : roomNumber};
        };
    }

    /**
     * Returns one message argument by position for templates (Thymeleaf does not spread an argument array); a missing
     * position yields an empty string, which the message pattern ignores.
     *
     * @param index zero-based argument position
     * @return the argument, or an empty string
     */
    public Object messageArgument(int index) {
        Object[] arguments = messageArguments();
        return index < arguments.length ? arguments[index] : "";
    }
}
