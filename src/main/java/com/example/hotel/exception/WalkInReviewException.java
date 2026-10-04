package com.example.hotel.exception;

/**
 * Raised when the Walk-in review rejects the submitted selections before anything is created. It is a user-facing
 * rejection that carries no display text: the presentation layer resolves the {@link Reason} in the active locale and
 * shows it next to the field it concerns.
 */
public class WalkInReviewException extends RuntimeException {

    /** Why the Walk-in selections were rejected. */
    public enum Reason {
        /** The check-out date is not after the Walk-in check-in date (the hotel current date). */
        CHECK_OUT_NOT_AFTER_CHECK_IN,
        /** The same Room was selected more than once. */
        DUPLICATE_ROOM,
        /** A selected Room is not active AVAILABLE and free for the whole stay. */
        ROOM_UNAVAILABLE,
        /** The adults exceed the adult capacity of the selected Rooms. */
        INSUFFICIENT_ADULT_CAPACITY,
        /** A selected Room's Room Type has no configured capacity. */
        CAPACITY_NOT_CONFIGURED
    }

    private final Reason reason;
    private final transient Object[] arguments;

    /**
     * Creates the exception.
     *
     * @param reason why the selections are rejected
     * @param message English detail for logs
     * @param arguments values interpolated into the localized message
     */
    public WalkInReviewException(Reason reason, String message, Object... arguments) {
        super(message);
        this.reason = reason;
        this.arguments = arguments;
    }

    /**
     * Returns why the selections were rejected.
     *
     * @return the rejection reason
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * Returns the values interpolated into the localized message.
     *
     * @return the message arguments, possibly empty
     */
    public Object[] getArguments() {
        return arguments;
    }
}
