package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only context of the Extend Stay form.
 *
 * @param reservationId Reservation identifier
 * @param reservationNumber Reservation number
 * @param guestName Primary Guest display name
 * @param guestCode Primary Guest code
 * @param currency Reservation currency
 * @param currentCheckOutDate current planned check-out date
 * @param earliestNewCheckOutDate earliest valid new check-out date
 * @param rooms one line per current room
 * @param outstanding current outstanding balance, or {@code null} when the caller may not see payment data
 */
public record StayExtensionFormResponse(
        UUID reservationId,
        String reservationNumber,
        String guestName,
        String guestCode,
        String currency,
        LocalDate currentCheckOutDate,
        LocalDate earliestNewCheckOutDate,
        List<Line> rooms,
        BigDecimal outstanding) {

    /**
     * One current room of the Stay.
     *
     * @param roomNumber current room number
     * @param roomTypeName room type name, or {@code null}
     * @param nightlyRate original booked nightly rate of the room's lineage (the extension rate)
     */
    public record Line(String roomNumber, String roomTypeName, BigDecimal nightlyRate) {}
}
