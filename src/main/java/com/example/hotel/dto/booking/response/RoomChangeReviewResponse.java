package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.RoomChangeReason;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies the read-only Room Change Review data shown before the atomic Confirm step.
 *
 * @param reservationId the reservation identifier
 * @param reservationNumber the external reservation number
 * @param guestName the primary guest's display name, or an empty string when none is recorded
 * @param checkInDate the reservation's check-in date
 * @param plannedCheckOutDate the lineage's remaining planned check-out boundary
 * @param nights the number of nights between check-in and planned check-out
 * @param adultCount the number of adults on the reservation
 * @param childCount the number of children on the reservation
 * @param currentRoom the room currently occupied, being replaced
 * @param targetRoom the selected replacement room
 * @param reason the submitted Room Change reason
 * @param notes the submitted optional notes
 */
public record RoomChangeReviewResponse(
        UUID reservationId,
        String reservationNumber,
        String guestName,
        LocalDate checkInDate,
        LocalDate plannedCheckOutDate,
        int nights,
        int adultCount,
        int childCount,
        RoomChangeReviewRoom currentRoom,
        RoomChangeReviewRoom targetRoom,
        RoomChangeReason reason,
        String notes) {}
