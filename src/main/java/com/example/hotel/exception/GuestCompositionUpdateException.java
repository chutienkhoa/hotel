package com.example.hotel.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Raised when a CONFIRMED Reservation's guest-composition update is rejected. It is still a
 * {@link ResponseStatusException} (with an English detail) so REST callers get the usual status, and it also carries
 * the machine reason and message arguments so the MVC layer can localize the text.
 */
public class GuestCompositionUpdateException extends ResponseStatusException {

    /** Why the update was rejected. */
    public enum Reason {
        /** The Reservation is not CONFIRMED. */
        RESERVATION_NOT_CONFIRMED(HttpStatus.CONFLICT),
        /** A Stay already exists for the Reservation. */
        STAY_ALREADY_EXISTS(HttpStatus.CONFLICT),
        /** The new adult count exceeds the adult capacity of the assigned rooms. */
        INSUFFICIENT_ADULT_CAPACITY(HttpStatus.CONFLICT),
        /** An assigned room's RoomType has no configured capacity. */
        CAPACITY_NOT_CONFIGURED(HttpStatus.CONFLICT),
        /** Adults is below 1. */
        INVALID_ADULT_COUNT(HttpStatus.BAD_REQUEST),
        /** Children is negative. */
        INVALID_CHILD_COUNT(HttpStatus.BAD_REQUEST),
        /** An accompanying guest identifier does not exist. */
        GUEST_NOT_FOUND(HttpStatus.NOT_FOUND),
        /** A guest appears more than once. */
        DUPLICATE_GUEST(HttpStatus.BAD_REQUEST),
        /** The Primary Guest appears in the accompanying guests. */
        PRIMARY_GUEST_AS_ACCOMPANYING(HttpStatus.BAD_REQUEST);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }
    }

    private final Reason reason;
    private final List<Object> arguments;

    /**
     * Creates the exception.
     *
     * @param reason why the update is rejected
     * @param detail English detail (used as the REST reason)
     * @param arguments localized-message arguments (adults and capacity, or room type names)
     */
    public GuestCompositionUpdateException(Reason reason, String detail, Object... arguments) {
        super(reason.status, detail);
        this.reason = reason;
        this.arguments = List.of(arguments);
    }

    /**
     * Returns why the update was rejected.
     *
     * @return the reason
     */
    public Reason getUpdateReason() {
        return reason;
    }

    /**
     * Returns the message arguments for localization.
     *
     * @return the arguments, possibly empty
     */
    public List<Object> getArguments() {
        return arguments;
    }
}
