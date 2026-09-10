package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the reservation fields required by the reservation detail page.
 *
 * @param id the reservation identifier
 * @param reservationNumber the external reservation number
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param status the current reservation status
 * @param checkInDate the planned check-in date
 * @param checkOutDate the planned check-out date
 * @param totalAmount the snapshot total amount
 * @param currency the three-letter currency code
 * @param notes the optional reservation notes
 * @param rooms the assigned-room snapshots
 */
public record ReservationDetailResponse(
        UUID id,
        UUID reservationNumber,
        UUID guestId,
        String guestCode,
        String status,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal totalAmount,
        String currency,
        String notes,
        List<ReservationRoomResponse> rooms) {
}
