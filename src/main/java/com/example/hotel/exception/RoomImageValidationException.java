package com.example.hotel.exception;

/**
 * Signals a browser-safe validation failure for a Room image upload batch. Carries both a stable
 * English default message and a semantic message key, so the presentation layer can resolve the
 * localized text through the existing EN/VI message bundles (see {@code UiMessages}).
 */
public class RoomImageValidationException extends RuntimeException {

    private final String messageKey;

    /**
     * Creates the validation exception with its message key and browser-safe English default.
     *
     * @param messageKey semantic key in the EN/VI message bundles
     * @param message English default message describing the invalid upload
     */
    public RoomImageValidationException(String messageKey, String message) {
        super(message);
        this.messageKey = messageKey;
    }

    /**
     * Returns the semantic message key used to resolve localized presentation text.
     *
     * @return the message key
     */
    public String getMessageKey() {
        return messageKey;
    }
}
