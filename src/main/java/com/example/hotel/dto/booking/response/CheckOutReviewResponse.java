package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the read-only Check-out Review for one Reservation. Current rooms come from open
 * {@code StayRoomAssignment} rows, never the original {@code ReservationRoom} booking. This
 * Review is presentation only: the final Confirm action independently re-validates every
 * precondition (Reservation/Stay status, Outstanding, current Room state) through the existing
 * authoritative {@code ReservationService.checkOut}.
 *
 * @param reservationId the reservation identifier
 * @param reservationNumber the external reservation number
 * @param status the current reservation status
 * @param eligibleForCheckOut {@code true} only when status is CHECKED_IN and readiness is READY
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param currentRooms the Stay's current room assignments
 * @param checkInDate the reservation's planned check-in date
 * @param checkOutDate the reservation's planned check-out date
 * @param actualCheckInAt the Stay's backend-recorded actual check-in time
 * @param readiness {@code READY} or {@code PAYMENT_REQUIRED}, from the authoritative StayBalanceService
 */
public record CheckOutReviewResponse(
        UUID reservationId,
        String reservationNumber,
        String status,
        boolean eligibleForCheckOut,
        UUID guestId,
        String guestCode,
        List<CurrentRoomResponse> currentRooms,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Instant actualCheckInAt,
        String readiness) {}
