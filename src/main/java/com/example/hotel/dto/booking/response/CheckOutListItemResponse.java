package com.example.hotel.dto.booking.response;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies one row of the Check-out operational search results: a CHECKED_IN Reservation with
 * its Stay's CURRENT rooms (from open {@code StayRoomAssignment} rows, never the original
 * {@code ReservationRoom} booking) and a non-financial readiness indicator.
 *
 * @param id the reservation identifier
 * @param reservationNumber the external reservation number
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param currentRoomNumbers the Stay's current room numbers in a compact, comma-separated form
 * @param checkOutDate the reservation's planned check-out date
 * @param readiness {@code READY} or {@code PAYMENT_REQUIRED}, from the authoritative StayBalanceService
 */
public record CheckOutListItemResponse(
        UUID id,
        String reservationNumber,
        UUID guestId,
        String guestCode,
        String currentRoomNumbers,
        LocalDate checkOutDate,
        String readiness) {}
