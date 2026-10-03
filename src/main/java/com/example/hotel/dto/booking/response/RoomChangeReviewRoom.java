package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * Describes one room in the Room Change Review comparison. Read-only display data: the statuses are the room's
 * status today and the status {@code changeRoom} gives it on confirmation, so the Review never invents a transition.
 *
 * @param id the room identifier
 * @param roomNumber the room's display number
 * @param roomTypeName the room's Room Type name
 * @param capacity the room's Room Type capacity, when defined
 * @param status the room's status before the change is confirmed
 * @param resultingStatus the status the room will have after the change is confirmed
 * @param hasPrimaryImage whether the room has a primary image to show
 */
public record RoomChangeReviewRoom(
        UUID id,
        String roomNumber,
        String roomTypeName,
        Integer capacity,
        String status,
        String resultingStatus,
        boolean hasPrimaryImage) {}
