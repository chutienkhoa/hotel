package com.example.hotel.dto.booking.response;

/**
 * Tells Reservation Detail which date-dependent lifecycle actions the backend would currently accept, so the page can
 * avoid offering an action that is guaranteed to be rejected. The values come from the same rules the mutating
 * operations enforce; they are guidance only and every operation re-validates under its own locks.
 *
 * @param checkInEligible whether Check-in would pass its Reservation-level checks (CONFIRMED, not early, no Stay yet)
 * @param noShowEligible whether the Reservation may currently be marked no-show (CONFIRMED and the check-in date has passed)
 */
public record ReservationDetailEligibility(boolean checkInEligible, boolean noShowEligible) {

    /** The eligibility used when none can be determined: nothing is offered. */
    public static final ReservationDetailEligibility NONE = new ReservationDetailEligibility(false, false);
}
