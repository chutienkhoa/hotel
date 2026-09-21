package com.example.hotel.dto.booking.response;

/**
 * Classifies the hotel current date against a Reservation's planned check-in date for the
 * Check-in Review page.
 */
public enum CheckInTiming {
    /** Hotel current date is before the planned check-in date; check-in must be blocked. */
    EARLY,
    /** Hotel current date equals the planned check-in date. */
    NORMAL,
    /** Hotel current date is after the planned check-in date; check-in is allowed with a warning. */
    LATE
}
