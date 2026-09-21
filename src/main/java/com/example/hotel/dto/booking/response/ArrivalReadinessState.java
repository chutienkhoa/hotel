package com.example.hotel.dto.booking.response;

/** Derived overall Arrival Readiness of a Reservation. Never persisted and separate from ReservationStatus. */
public enum ArrivalReadinessState {
    /** No blocker: check-in would be accepted right now. */
    READY,
    /** At least one blocker prevents check-in right now. */
    NEEDS_ATTENTION
}
