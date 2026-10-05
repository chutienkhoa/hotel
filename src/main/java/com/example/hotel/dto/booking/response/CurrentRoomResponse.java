package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Supplies one currently occupied room for a CHECKED_IN Reservation, sourced from the open
 * StayRoomAssignment for that room rather than the original ReservationRoom booking snapshot.
 *
 * @param assignmentId the open assignment identifier
 * @param roomId the currently occupied room identifier
 * @param roomNumber the currently occupied room's display number
 * @param assignedFrom the instant this room became current
 * @param roomTypeName the room's Room Type name, or {@code null} when it is not resolved
 * @param adultCapacity the Room Type's adult capacity, or {@code null} when it is not configured
 */
public record CurrentRoomResponse(
        UUID assignmentId,
        UUID roomId,
        String roomNumber,
        Instant assignedFrom,
        String roomTypeName,
        Integer adultCapacity) {

    /**
     * Creates a current room without Room Type presentation data, for callers that only need the assignment.
     *
     * @param assignmentId the open assignment identifier
     * @param roomId the currently occupied room identifier
     * @param roomNumber the currently occupied room's display number
     * @param assignedFrom the instant this room became current
     */
    public CurrentRoomResponse(UUID assignmentId, UUID roomId, String roomNumber, Instant assignedFrom) {
        this(assignmentId, roomId, roomNumber, assignedFrom, null, null);
    }
}
