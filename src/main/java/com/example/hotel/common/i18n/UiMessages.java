package com.example.hotel.common.i18n;

import com.example.hotel.exception.LocalizedResponseStatusException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Presentation-boundary access to translated text. Business and domain code never depends on this
 * class or on the locale: they raise message keys, and the presentation layer resolves them here
 * through the Spring {@link MessageSource}. Explicit-locale overloads let non-web consumers (such as
 * future report exports) reuse the same labels.
 */
public final class UiMessages {

    private final MessageSource messageSource;

    /**
     * Creates the resolver.
     *
     * @param messageSource the application message source
     */
    public UiMessages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves a message in the current request locale.
     *
     * @param key message key
     * @param args message arguments
     * @return the translated text, or the key when it is missing everywhere
     */
    public String get(String key, Object... args) {
        return get(LocaleContextHolder.getLocale(), key, args);
    }

    /**
     * Resolves a message in an explicit locale, falling back to the English base bundle.
     *
     * @param locale the locale to use
     * @param key message key
     * @param args message arguments
     * @return the translated text, or the key when it is missing everywhere
     */
    public String get(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, key, locale);
    }

    /**
     * Resolves the display label of an enum-like internal value. The stored value never changes.
     *
     * @param semanticType semantic type, for example {@code reservationStatus}
     * @param value internal value, for example {@code CHECKED_IN}
     * @return the label in the current locale, or the raw value when no label exists
     */
    public String enumLabel(String semanticType, String value) {
        return enumLabel(LocaleContextHolder.getLocale(), semanticType, value);
    }

    /**
     * Resolves an enum display label in an explicit locale.
     *
     * @param locale the locale to use
     * @param semanticType semantic type
     * @param value internal value
     * @return the label, or the raw value when no label exists
     */
    public String enumLabel(Locale locale, String semanticType, String value) {
        if (value == null) {
            return "";
        }
        return messageSource.getMessage("enum." + semanticType + "." + value, null, value, locale);
    }

    /**
     * Produces a browser-safe message for a service exception: a key-carrying exception is
     * translated, any other exception keeps its existing reason.
     *
     * @param exception the exception raised by an operation
     * @return the message to display
     */
    public String error(ResponseStatusException exception) {
        if (exception instanceof LocalizedResponseStatusException localized) {
            try {
                return messageSource.getMessage(localized.getMessageKey(), localized.getMessageArguments(), LocaleContextHolder.getLocale());
            } catch (NoSuchMessageException missing) {
                return exception.getReason();
            }
        }
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
