package com.example.hotel.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Resolves the PMS UI locale from a small cookie. Only Vietnamese and English are supported.
 * Without a valid cookie the locale is Vietnamese; the browser's Accept-Language is deliberately
 * ignored. {@code /api/**} is always English so REST behaviour never depends on the UI cookie.
 */
public class PmsLocaleResolver extends CookieLocaleResolver {

    /** Cookie carrying the selected PMS language. It holds only {@code vi} or {@code en}. */
    public static final String COOKIE_NAME = "pms-lang";

    /** The runtime default PMS UI language. */
    public static final Locale DEFAULT_LOCALE = Locale.of("vi");

    /** The fallback and API language. */
    public static final Locale ENGLISH = Locale.ENGLISH;

    private static final List<Locale> SUPPORTED = List.of(DEFAULT_LOCALE, ENGLISH);

    private final Locale defaultLocale;

    /**
     * Creates the resolver with the approved cookie settings.
     *
     * @param secureCookie whether the cookie is restricted to HTTPS
     */
    public PmsLocaleResolver(boolean secureCookie) {
        this(secureCookie, DEFAULT_LOCALE);
    }

    /**
     * Creates the resolver with an explicit default language. The runtime default is Vietnamese; the
     * test environment pins English through {@code hotel.i18n.default-locale}.
     *
     * @param secureCookie whether the cookie is restricted to HTTPS
     * @param defaultLocale language used when no valid cookie exists (unsupported values become Vietnamese)
     */
    public PmsLocaleResolver(boolean secureCookie, Locale defaultLocale) {
        super(COOKIE_NAME);
        Locale effectiveDefault = supported(defaultLocale) == null ? DEFAULT_LOCALE : supported(defaultLocale);
        this.defaultLocale = effectiveDefault;
        setDefaultLocaleFunction(request -> effectiveDefault);
        setRejectInvalidCookies(false);
        setCookiePath("/");
        setCookieMaxAge(Duration.ofDays(365));
        setCookieHttpOnly(true);
        setCookieSecure(secureCookie);
        setCookieSameSite("Lax");
    }

    /**
     * Normalizes a requested locale to a supported one.
     *
     * @param locale any locale, possibly {@code null}
     * @return {@code vi} or {@code en}, or {@code null} when the language is not supported
     */
    public static Locale supported(Locale locale) {
        if (locale == null) {
            return null;
        }
        return SUPPORTED.stream()
                .filter(candidate -> candidate.getLanguage().equals(locale.getLanguage()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (isApiRequest(request)) {
            return ENGLISH;
        }
        Locale resolved = supported(super.resolveLocale(request));
        return resolved == null ? defaultLocale : resolved;
    }

    @Override
    public LocaleContext resolveLocaleContext(HttpServletRequest request) {
        return new SimpleLocaleContext(resolveLocale(request));
    }

    @Override
    public void setLocaleContext(
            HttpServletRequest request, HttpServletResponse response, LocaleContext localeContext) {
        if (isApiRequest(request)) {
            return;
        }
        Locale requested = localeContext == null ? null : supported(localeContext.getLocale());
        if (requested == null) {
            return;
        }
        super.setLocaleContext(request, response, new SimpleLocaleContext(requested));
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/api") || path.startsWith("/api/");
    }
}
