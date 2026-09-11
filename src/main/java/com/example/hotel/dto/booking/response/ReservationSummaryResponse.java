package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies the reservation fields required by the reservation list page.
 *
 * @param id the reservation identifier
 * @param reservationNumber the external reservation number
 * @param status the current reservation status
 * @param checkInDate the planned check-in date
 * @param checkOutDate the planned check-out date
 * @param totalAmount the snapshot total amount
 * @param currency the three-letter currency code
 */
public record ReservationSummaryResponse(
        UUID id,
        String reservationNumber,
        String status,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal totalAmount,
        String currency) {
}
