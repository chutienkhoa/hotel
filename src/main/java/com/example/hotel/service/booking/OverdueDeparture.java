package com.example.hotel.service.booking;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The single authoritative rule of an Overdue Departure: a CHECKED_IN stay whose current planned check-out date
 * ({@code Reservation.checkOutDate}) is before the hotel date. It is derived, never persisted. Front Desk, the Check-out
 * Review and the check-out command all use it, so they cannot disagree.
 */
public final class OverdueDeparture {

    private OverdueDeparture() {}

    /**
     * Whole hotel days by which the planned check-out is in the past.
     *
     * @param plannedCheckOut current planned check-out date
     * @param hotelToday hotel-local date from the injected Clock
     * @return {@code max(0, days between)}: 0 when the check-out is today or later, 1 when it was yesterday
     */
    public static long overdueDays(LocalDate plannedCheckOut, LocalDate hotelToday) {
        return Math.max(0, ChronoUnit.DAYS.between(plannedCheckOut, hotelToday));
    }

    /**
     * Tells whether a planned check-out date is overdue.
     *
     * @param plannedCheckOut current planned check-out date
     * @param hotelToday hotel-local date
     * @return {@code true} when the planned check-out is before the hotel date
     */
    public static boolean isOverdue(LocalDate plannedCheckOut, LocalDate hotelToday) {
        return plannedCheckOut.isBefore(hotelToday);
    }
}
