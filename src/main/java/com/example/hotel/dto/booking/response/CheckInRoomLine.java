package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies one assigned-room line for a Check-in Review page (Existing Reservation or Walk-in).
 *
 * @param roomId the assigned room identifier, used to link the room number to its Room Detail page
 * @param roomNumber the assigned room's business number
 * @param roomTypeName the assigned room's RoomType name, when available
 * @param checkInDate the room's snapshot check-in date
 * @param checkOutDate the room's snapshot check-out date
 * @param nightlyRate the snapshot nightly rate
 * @param nights the number of booked nights for this room
 * @param totalAmount the snapshot room total
 * @param roomTypeCapacity the assigned Room's RoomType adult capacity, or {@code null} when not configured
 * @param roomStatus the Room's current live status (e.g. {@code AVAILABLE}, {@code DIRTY})
 */
public record CheckInRoomLine(
        UUID roomId,
        String roomNumber,
        String roomTypeName,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal nightlyRate,
        long nights,
        BigDecimal totalAmount,
        Integer roomTypeCapacity,
        String roomStatus) {
}
