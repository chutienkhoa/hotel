package com.example.hotel.dto.room.response;

import java.util.UUID;

/**
 * Supplies the minimal room data required by the reservation create form.
 *
 * <p>Room Type and adult capacity describe the Room itself. The current room status is operational information only:
 * it says nothing about whether the Room is bookable for a future period, which is answered by the date-aware lookup
 * returning the Room at all.</p>
 *
 * @param id the room identifier submitted in a reservation request
 * @param roomNumber the room's display number
 * @param status the current room status
 * @param active whether the room is active
 * @param roomTypeName the Room Type display name, or {@code null} when it is unavailable to the caller
 * @param adultCapacity the Room Type's ADULT capacity, or {@code null} when it is not configured
 */
public record RoomLookupResponse(
        UUID id, String roomNumber, String status, boolean active, String roomTypeName, Integer adultCapacity) {

    /**
     * Creates a lookup entry without Room Type data, for callers that only need the room identity and status.
     *
     * @param id the room identifier
     * @param roomNumber the room's display number
     * @param status the current room status
     * @param active whether the room is active
     */
    public RoomLookupResponse(UUID id, String roomNumber, String status, boolean active) {
        this(id, roomNumber, status, active, null, null);
    }
}
