package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Supplies one currently occupied room for a CHECKED_IN Reservation, sourced from the open
 * StayRoomAssignment for that room rather than the original ReservationRoom booking snapshot.
 *
 * @param assignmentId the open assignment identifier, used as the Room Change entry-point key
 * @param roomId the currently occupied room identifier
 * @param roomNumber the currently occupied room's display number
 * @param assignedFrom the instant this room became current
 */
public record CurrentRoomResponse(UUID assignmentId, UUID roomId, String roomNumber, Instant assignedFrom) {}
