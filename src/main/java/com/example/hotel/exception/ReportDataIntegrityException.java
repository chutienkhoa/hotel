package com.example.hotel.exception;

import java.util.UUID;

/**
 * Raised when data used by a report violates a domain invariant (for example a ReservationRoom whose
 * total is not nightly rate x booked nights). The report fails deterministically instead of silently
 * choosing or repairing a value. The detail is for logs; the UI shows a friendly translated message.
 */
public class ReportDataIntegrityException extends RuntimeException {

    private final UUID reservationRoomId;

    /**
     * Creates the exception.
     *
     * @param reservationRoomId the offending ReservationRoom
     * @param message English detail for logs
     */
    public ReportDataIntegrityException(UUID reservationRoomId, String message) {
        super(message);
        this.reservationRoomId = reservationRoomId;
    }

    /**
     * Creates the exception for an integrity violation that is not tied to one ReservationRoom.
     *
     * @param message English detail for logs
     */
    public ReportDataIntegrityException(String message) {
        this(null, message);
    }

    /**
     * Returns the offending ReservationRoom identifier.
     *
     * @return the ReservationRoom identifier, or {@code null} when the violation is not tied to one
     */
    public UUID getReservationRoomId() {
        return reservationRoomId;
    }
}
