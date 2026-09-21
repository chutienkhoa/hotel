package com.example.hotel.controller.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Verifies the safety net that recovers an oversized Guest passport upload rejected while Spring
 * Security reads the CSRF request parameter, before Spring MVC dispatch (and therefore before
 * {@link GuestMultipartExceptionHandler}) ever runs.
 */
class GuestMultipartUploadFilterTest {

    private final GuestMultipartUploadFilter filter = new GuestMultipartUploadFilter();

    /** Confirms an oversized Create Guest upload redirects with the friendly flash message. */
    @Test
    void shouldRedirectOversizedCreateUploadWithFriendlyFlashMessage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = request("/guests", session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = throwing(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        filter.doFilter(request, response, failingChain);

        assertEquals("/guests/new", response.getRedirectedUrl());
        assertEquals("Passport image must not exceed 5 MB.", retrieveFlashMessage(session, "/guests/new"));
    }

    /** Confirms an oversized replacement redirects to the corresponding Guest edit form. */
    @Test
    void shouldRedirectOversizedReplacementToGuestEditFormWithFriendlyMessage() throws Exception {
        UUID guestId = UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = request("/guests/" + guestId, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = throwing(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        filter.doFilter(request, response, failingChain);

        assertEquals("/guests/" + guestId + "/edit", response.getRedirectedUrl());
        assertEquals(
                "Passport image must not exceed 5 MB.",
                retrieveFlashMessage(session, "/guests/" + guestId + "/edit"));
    }

    /** Confirms a plain, non-size-related {@link MultipartException} is still handled the same way. */
    @Test
    void shouldAlsoRecoverFromGenericMultipartException() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = request("/guests", session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = throwing(new MultipartException("Failed to parse multipart servlet request"));

        filter.doFilter(request, response, failingChain);

        assertEquals("/guests/new", response.getRedirectedUrl());
    }

    /** Confirms an oversized upload on an unrelated route is rethrown rather than silently redirected. */
    @Test
    void shouldRethrowMultipartFailureForNonGuestRoutes() {
        MockHttpServletRequest request = request("/additional-revenues", new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = throwing(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertThrows(MaxUploadSizeExceededException.class, () -> filter.doFilter(request, response, failingChain));
        assertNull(response.getRedirectedUrl());
    }

    /** Confirms an unrelated exception from the filter chain is never caught by this filter. */
    @Test
    void shouldNotInterceptUnrelatedExceptions() {
        MockHttpServletRequest request = request("/guests", new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("unrelated failure");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, failingChain));
        assertNull(response.getRedirectedUrl());
    }

    /** Builds a POST request against the given path, sharing the given session. */
    private MockHttpServletRequest request(String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setSession(session);
        return request;
    }

    /** Builds a filter chain that unconditionally throws the given exception. */
    private FilterChain throwing(RuntimeException exception) {
        return (req, res) -> {
            throw exception;
        };
    }

    /**
     * Reads back the flash message saved for the given target path, simulating the next request
     * the browser makes after following the redirect.
     */
    private String retrieveFlashMessage(MockHttpSession session, String targetPath) {
        MockHttpServletRequest nextRequest = new MockHttpServletRequest("GET", targetPath);
        nextRequest.setRequestURI(targetPath);
        nextRequest.setSession(session);
        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        FlashMap flashMap = new SessionFlashMapManager().retrieveAndUpdate(nextRequest, nextResponse);
        return flashMap == null ? null : (String) flashMap.get("passportError");
    }
}
