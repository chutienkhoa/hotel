package com.example.hotel.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies the cookie locale rules: Vietnamese default, only vi/en, no Accept-Language, English API. */
class PmsLocaleResolverTest {

    private final PmsLocaleResolver resolver = new PmsLocaleResolver(false);

    private MockHttpServletRequest request(String uri, String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (cookieValue != null) {
            request.setCookies(new Cookie(PmsLocaleResolver.COOKIE_NAME, cookieValue));
        }
        return request;
    }

    /** Confirms no cookie means Vietnamese and the browser language is ignored. */
    @Test
    void shouldDefaultToVietnameseAndIgnoreAcceptLanguage() {
        MockHttpServletRequest request = request("/staff", null);
        request.addPreferredLocale(Locale.FRENCH);
        request.addHeader("Accept-Language", "en-US,en;q=0.9");

        assertEquals("vi", resolver.resolveLocale(request).getLanguage());
    }

    /** Confirms a valid cookie is honoured. */
    @Test
    void shouldHonourSupportedCookies() {
        assertEquals("en", resolver.resolveLocale(request("/staff", "en")).getLanguage());
        assertEquals("vi", resolver.resolveLocale(request("/staff", "vi")).getLanguage());
    }

    /** Confirms unsupported or malformed cookie values fall back to the default. */
    @Test
    void shouldRejectUnsupportedOrMalformedCookies() {
        assertEquals("vi", resolver.resolveLocale(request("/staff", "fr")).getLanguage());
        assertEquals("vi", resolver.resolveLocale(request("/staff", "../../x")).getLanguage());
        assertEquals("vi", resolver.resolveLocale(request("/staff", "")).getLanguage());
    }

    /** Confirms only supported languages are written to the cookie, with the approved attributes. */
    @Test
    void shouldSetOnlySupportedLocalesWithSafeCookieAttributes() {
        MockHttpServletRequest request = request("/staff", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setLocaleContext(request, response, new SimpleLocaleContext(Locale.FRENCH));
        assertNull(response.getHeader("Set-Cookie"));

        resolver.setLocaleContext(request, response, new SimpleLocaleContext(Locale.ENGLISH));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("pms-lang=en"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertTrue(cookie.contains("Max-Age="), cookie);
    }

    /** Confirms the secure flag is configurable. */
    @Test
    void shouldSupportSecureCookieConfiguration() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PmsLocaleResolver(true).setLocaleContext(request("/staff", null), response, new SimpleLocaleContext(Locale.ENGLISH));

        assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
    }

    /** Confirms /api/** is always English and never written to by the UI language switch. */
    @Test
    void shouldAlwaysUseEnglishForApiRequests() {
        assertEquals("en", resolver.resolveLocale(request("/api/expenses", "vi")).getLanguage());
        assertEquals("en", resolver.resolveLocale(request("/api/auth/login", null)).getLanguage());
        MockHttpServletResponse response = new MockHttpServletResponse();
        resolver.setLocaleContext(request("/api/x", null), response, new SimpleLocaleContext(Locale.of("vi")));
        assertNull(response.getHeader("Set-Cookie"));
    }

    /** Confirms the test-environment default can be pinned to English while unsupported defaults become Vietnamese. */
    @Test
    void shouldAllowPinningDefaultLanguage() {
        assertEquals("en", new PmsLocaleResolver(false, Locale.ENGLISH).resolveLocale(request("/staff", null)).getLanguage());
        assertEquals("vi", new PmsLocaleResolver(false, Locale.FRENCH).resolveLocale(request("/staff", null)).getLanguage());
    }
}
