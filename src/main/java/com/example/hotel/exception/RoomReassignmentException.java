package com.example.hotel.exception;

/**
 * Raised when a pre-check-in Room reassignment is rejected because the Reservation or the requested Rooms are no
 * longer in a state that allows it. It is a user-facing rejection that carries no display text.
 */
public class RoomReassignmentException extends RuntimeException {

    /** Why the reassignment was rejected. */
    public enum Reason {
        /** The Reservation is not CONFIRMED any more. */
        RESERVATION_STATE_CHANGED,
        /** A Stay already exists for the Reservation. */
        STAY_ALREADY_EXISTS,
        /** The assignment being replaced is no longer on the Reservation. */
        ASSIGNMENT_CHANGED,
        /** The replacement Room is the current Room or already assigned to the Reservation. */
        ROOM_ALREADY_ASSIGNED,
        /** The replacement Room is not active AVAILABLE and free for the booked dates. */
        ROOM_UNAVAILABLE,
        /** The Reservation's adults would exceed the adult capacity of the resulting room set. */
        INSUFFICIENT_ADULT_CAPACITY,
        /** A room of the resulting set has a RoomType without configured capacity. */
        CAPACITY_NOT_CONFIGURED
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the reassignment is rejected
     * @param message English detail for logs
     */
    public RoomReassignmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns why the reassignment was rejected.
     *
     * @return the rejection reason
     */
    public Reason getReason() {
        return reason;
    }
}
