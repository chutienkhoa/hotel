package com.example.hotel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reports a structured rejection from one of the two controlled narrow Reservation field updates that remain
 * available through CHECKED_IN — Booking Contact and Reservation Notes — while retaining a stable English REST
 * error detail.
 */
public class ReservationFieldUpdateException extends ResponseStatusException {

    /** Identifies the business rule that rejected the requested field update. */
    public enum Reason {
        /** The Reservation is CHECKED_OUT, CANCELLED or NO_SHOW, so this narrow field is no longer editable. */
        RESERVATION_LOCKED(HttpStatus.CONFLICT);

        private final HttpStatus status;

        /** Associates the reason with its stable REST status. */
        Reason(HttpStatus status) {
            this.status = status;
        }
    }

    private final Reason fieldUpdateReason;

    /**
     * Creates a structured business rejection.
     *
     * @param reason rejected business condition
     * @param detail stable English REST detail
     */
    public ReservationFieldUpdateException(Reason reason, String detail) {
        super(reason.status, detail);
        this.fieldUpdateReason = reason;
    }

    /**
     * Returns the rejected business condition.
     *
     * @return rejection reason
     */
    public Reason getFieldUpdateReason() {
        return fieldUpdateReason;
    }
}
