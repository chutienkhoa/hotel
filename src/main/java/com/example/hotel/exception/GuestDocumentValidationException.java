package com.example.hotel.exception;

/** Signals a browser-safe validation failure for an optional Guest passport image upload. */
public class GuestDocumentValidationException extends RuntimeException {

    /**
     * Creates the validation exception with its browser-safe message.
     *
     * @param message message describing the invalid upload
     */
    public GuestDocumentValidationException(String message) {
        super(message);
    }
}
