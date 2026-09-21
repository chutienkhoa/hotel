package com.example.hotel.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Raised when a Stay Extension is rejected. It is still a {@link ResponseStatusException} (with an English detail) so
 * REST callers get the usual status, and it also carries the machine reason and message arguments so the MVC layer
 * can localize the text.
 */
public class StayExtensionException extends ResponseStatusException {

    /** Why the extension was rejected. */
    public enum Reason {
        /** The Reservation is not CHECKED_IN. */
        RESERVATION_NOT_CHECKED_IN(HttpStatus.CONFLICT),
        /** The CHECKED_IN Reservation has no Stay. */
        STAY_NOT_FOUND(HttpStatus.CONFLICT),
        /** The Stay is not CHECKED_IN although the Reservation is. */
        STAY_NOT_ACTIVE(HttpStatus.CONFLICT),
        /** The Stay has no open room assignment. */
        NO_CURRENT_ROOM(HttpStatus.CONFLICT),
        /** The submitted expected check-out date is not the current planned check-out date. */
        STALE_CHECK_OUT_DATE(HttpStatus.CONFLICT),
        /** The new check-out date is missing or not after both the current planned check-out and today. */
        INVALID_NEW_CHECK_OUT_DATE(HttpStatus.BAD_REQUEST),
        /** A current room is already allocated to something else during the extension period. */
        INVENTORY_CONFLICT(HttpStatus.CONFLICT),
        /** An extension line amount would not be positive. */
        NON_POSITIVE_AMOUNT(HttpStatus.CONFLICT);

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
     * @param reason why the extension is rejected
     * @param detail English detail (used as the REST reason)
     * @param arguments localized-message arguments
     */
    public StayExtensionException(Reason reason, String detail, Object... arguments) {
        super(reason.status, detail);
        this.reason = reason;
        this.arguments = List.of(arguments);
    }

    /**
     * Returns why the extension was rejected.
     *
     * @return the rejection reason
     */
    public Reason getExtensionReason() {
        return reason;
    }

    /**
     * Returns the localized-message arguments.
     *
     * @return the arguments
     */
    public List<Object> getArguments() {
        return arguments;
    }
}
