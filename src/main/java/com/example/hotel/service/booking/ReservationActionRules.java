package com.example.hotel.service.booking;

import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;

/**
 * The single date rule behind marking a Reservation no-show. Pure functions with no persistence:
 * {@link ReservationService#noShow} enforces it and {@link ReservationDetailEligibilityService} reports it to
 * Reservation Detail, so the two cannot disagree.
 */
public final class ReservationActionRules {

    private ReservationActionRules() {}

    /**
     * A same-day or future arrival cannot be marked no-show: the planned check-in date must already be in the past.
     *
     * @param checkInDate planned check-in date
     * @param today hotel current date
     * @return {@code true} when the check-in date has passed
     */
    public static boolean noShowDateReached(LocalDate checkInDate, LocalDate today) {
        return checkInDate.isBefore(today);
    }

    /**
     * Combines the state and date rules for a no-show.
     *
     * @param status Reservation status
     * @param checkInDate planned check-in date
     * @param today hotel current date
     * @return {@code true} when the Reservation is CONFIRMED and its check-in date has passed
     */
    public static boolean noShowEligible(ReservationStatus status, LocalDate checkInDate, LocalDate today) {
        return status == ReservationStatus.CONFIRMED && noShowDateReached(checkInDate, today);
    }
}
