package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
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
 * @param eligibleForCheckOut {@code true} only when status is CHECKED_IN, readiness is READY and the stay is not overdue
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param currentRooms the Stay's current room assignments
 * @param checkInDate the reservation's planned check-in date
 * @param checkOutDate the reservation's planned check-out date
 * @param actualCheckInAt the Stay's backend-recorded actual check-in time
 * @param overdueDays whole days the planned check-out is before the hotel date (0 when not overdue)
 * @param hotelToday hotel-local date used for the overdue calculation
 * @param readiness {@code READY} or {@code PAYMENT_REQUIRED}, from the authoritative StayBalanceService
 * @param source the Reservation's booking source, or {@code null} when not supplied
 * @param otaBookingReference the external booking reference, or {@code null}
 * @param adultCount the Reservation's adult count
 * @param childCount the Reservation's child count
 * @param reservedAt when the Reservation was made, or {@code null}
 * @param roomTypeLabel distinct Room Type names of the CURRENT rooms (not the booked ones), or {@code null}
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
        String readiness,
        long overdueDays,
        LocalDate hotelToday,
        BookingSource source,
        String otaBookingReference,
        int adultCount,
        int childCount,
        Instant reservedAt,
        String roomTypeLabel) {

    /**
     * Creates a Review without the presentation-only Stay summary fields (source, guest counts, booking time and
     * current Room Type).
     *
     * @param reservationId the reservation identifier
     * @param reservationNumber the external reservation number
     * @param status the current reservation status
     * @param eligibleForCheckOut whether Confirm Check-out may be offered
     * @param guestId the associated guest identifier
     * @param guestCode the associated guest's display code
     * @param currentRooms the Stay's current room assignments
     * @param checkInDate the planned check-in date
     * @param checkOutDate the planned check-out date
     * @param actualCheckInAt the Stay's actual check-in time
     * @param readiness {@code READY} or {@code PAYMENT_REQUIRED}
     * @param overdueDays whole overdue days
     * @param hotelToday hotel-local date
     */
    public CheckOutReviewResponse(
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
            String readiness,
            long overdueDays,
            LocalDate hotelToday) {
        this(reservationId, reservationNumber, status, eligibleForCheckOut, guestId, guestCode, currentRooms,
                checkInDate, checkOutDate, actualCheckInAt, readiness, overdueDays, hotelToday, null, null, 0, 0,
                null, null);
    }

    /**
     * Returns the number of nights between the planned check-in and check-out dates.
     *
     * @return the planned nights, or {@code 0} when a date is missing
     */
    public long nights() {
        return checkInDate == null || checkOutDate == null
                ? 0L
                : java.time.temporal.ChronoUnit.DAYS.between(checkInDate, checkOutDate);
    }
}
