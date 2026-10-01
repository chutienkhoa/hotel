package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies the reservation fields required by the reservation list page.
 *
 * @param id the reservation identifier
 * @param reservationNumber the external reservation number
 * @param guestFullName the associated guest's display name
 * @param roomNumbers the assigned room numbers in a compact, comma-separated form
 * @param status the current reservation status
 * @param source the reservation's booking source
 * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
 * @param checkInDate the planned check-in date
 * @param checkOutDate the planned check-out date
 * @param totalAmount the snapshot total amount
 * @param currency the three-letter currency code
 */
public record ReservationSummaryResponse(
        UUID id,
        String reservationNumber,
        String guestFullName,
        String roomNumbers,
        String status,
        BookingSource source,
        String otaBookingReference,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal totalAmount,
        String currency) {
}
