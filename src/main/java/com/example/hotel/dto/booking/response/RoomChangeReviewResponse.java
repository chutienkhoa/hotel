package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.RoomChangeReason;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies the read-only Room Change Review data shown before the atomic Confirm step.
 *
 * @param reservationId the reservation identifier
 * @param reservationNumber the external reservation number
 * @param currentRoomId the room currently occupied, being replaced
 * @param currentRoomNumber the current room's display number
 * @param targetRoomId the selected replacement room
 * @param targetRoomNumber the replacement room's display number
 * @param reason the submitted Room Change reason
 * @param notes the submitted optional notes
 * @param plannedCheckOutDate the lineage's remaining planned check-out boundary
 */
public record RoomChangeReviewResponse(
        UUID reservationId,
        String reservationNumber,
        UUID currentRoomId,
        String currentRoomNumber,
        UUID targetRoomId,
        String targetRoomNumber,
        RoomChangeReason reason,
        String notes,
        LocalDate plannedCheckOutDate) {}
