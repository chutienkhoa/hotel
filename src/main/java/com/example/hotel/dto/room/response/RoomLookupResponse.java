package com.example.hotel.dto.room.response;

import java.util.UUID;

/**
 * Supplies the minimal room data required by the reservation create form.
 *
 * @param id the room identifier submitted in a reservation request
 * @param roomNumber the room's display number
 * @param status the current room status
 * @param active whether the room is active
 */
public record RoomLookupResponse(UUID id, String roomNumber, String status, boolean active) {
}
