package com.example.hotel.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reports a structured rejection from one of the two controlled pre-check-in modifications of a confirmed
 * Reservation, while retaining a stable English REST error detail.
 */
public class ConfirmedReservationModificationException extends ResponseStatusException {

    /** Identifies the business rule that rejected the requested modification. */
    public enum Reason {
        /** The Reservation is no longer CONFIRMED. */
        RESERVATION_NOT_CONFIRMED(HttpStatus.CONFLICT),
        /** A Stay already exists, so the pre-check-in boundary is closed. */
        STAY_ALREADY_EXISTS(HttpStatus.CONFLICT),
        /** The requested check-in date precedes the current hotel date. */
        CHECK_IN_BEFORE_TODAY(HttpStatus.BAD_REQUEST),
        /** The requested check-out date does not follow check-in. */
        CHECK_OUT_NOT_AFTER_CHECK_IN(HttpStatus.BAD_REQUEST),
        /** At least one assigned Room conflicts with other inventory. */
        ROOM_UNAVAILABLE(HttpStatus.CONFLICT),
        /** Active prepayments would exceed the recalculated Reservation total. */
        PREPAYMENT_EXCEEDS_TOTAL(HttpStatus.CONFLICT),
        /** The operation was requested for a DIRECT Reservation. */
        DIRECT_RESERVATION(HttpStatus.CONFLICT),
        /** The corrected OTA reference is missing or blank. */
        OTA_REFERENCE_REQUIRED(HttpStatus.BAD_REQUEST),
        /** The corrected OTA reference exceeds the existing column/DTO limit. */
        OTA_REFERENCE_TOO_LONG(HttpStatus.BAD_REQUEST),
        /** The booked Room set changed while the operation was acquiring locks. */
        ROOMS_CHANGED_CONCURRENTLY(HttpStatus.CONFLICT);

        private final HttpStatus status;

        /** Associates the reason with its stable REST status. */
        Reason(HttpStatus status) {
            this.status = status;
        }
    }

    private final Reason modificationReason;
    private final List<Object> arguments;

    /**
     * Creates a structured business rejection.
     *
     * @param reason rejected business condition
     * @param detail stable English REST detail
     * @param arguments values used by localized MVC messages
     */
    public ConfirmedReservationModificationException(Reason reason, String detail, Object... arguments) {
        super(reason.status, detail);
        this.modificationReason = reason;
        this.arguments = List.of(arguments);
    }

    /**
     * Returns the rejected business condition.
     *
     * @return rejection reason
     */
    public Reason getModificationReason() {
        return modificationReason;
    }

    /**
     * Returns values for localized presentation messages.
     *
     * @return message arguments, possibly empty
     */
    public List<Object> getArguments() {
        return arguments;
    }
}
