package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies an assigned-room snapshot for reservation detail output.
 *
 * @param roomId the assigned room identifier
 * @param roomNumber the assigned room number
 * @param checkInDate the room check-in date
 * @param checkOutDate the room check-out date
 * @param nightlyRate the snapshot nightly rate
 * @param totalAmount the snapshot total amount
 */
public record ReservationRoomResponse(
        UUID roomId,
        String roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal nightlyRate,
        BigDecimal totalAmount) {
}
