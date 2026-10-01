package com.example.hotel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A {@link ResponseStatusException} that also carries a semantic message key and arguments. The
 * service raising it stays locale-independent; the presentation layer resolves the key in the active
 * locale (see {@code UiMessages#error}). HTTP status semantics are unchanged, and the English default
 * text is kept as the exception reason so logs and the API stay deterministic English.
 *
 * <p>Usage: {@code throw new LocalizedResponseStatusException(HttpStatus.CONFLICT,
 * "staff.error.cannotDeactivate", "Staff cannot deactivate from its current state");}</p>
 */
public class LocalizedResponseStatusException extends ResponseStatusException {

    private final String messageKey;
    private final transient Object[] messageArguments;

    /**
     * Creates the exception.
     *
     * @param status HTTP status
     * @param messageKey semantic key in the message bundles
     * @param defaultMessage English default text, used as the reason
     * @param messageArguments arguments for the message
     */
    public LocalizedResponseStatusException(
            HttpStatus status, String messageKey, String defaultMessage, Object... messageArguments) {
        super(status, defaultMessage);
        this.messageKey = messageKey;
        this.messageArguments = messageArguments;
    }

    /**
     * Returns the semantic message key.
     *
     * @return the message key
     */
    public String getMessageKey() {
        return messageKey;
    }

    /**
     * Returns the message arguments.
     *
     * @return the arguments, possibly empty
     */
    public Object[] getMessageArguments() {
        return messageArguments;
    }
}
