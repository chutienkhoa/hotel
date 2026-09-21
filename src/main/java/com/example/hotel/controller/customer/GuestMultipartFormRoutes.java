package com.example.hotel.controller.customer;

import java.util.UUID;

/**
 * Shares the oversized-passport-upload message and the Guest mutation URL-to-form-page mapping
 * between every place that must safely recover from a multipart size failure on a Guest route:
 * {@link GuestMultipartExceptionHandler} (a failure surfaced during normal Spring MVC dispatch)
 * and {@link GuestMultipartUploadFilter} (a failure surfaced earlier, while Spring Security reads
 * the CSRF request parameter from the same multipart body, before dispatch ever begins).
 */
final class GuestMultipartFormRoutes {

    /** Browser-safe message shown when any selected passport image exceeds the 5 MB business limit. */
    static final String PASSPORT_SIZE_MESSAGE = "Passport image must not exceed 5 MB.";

    private GuestMultipartFormRoutes() {}

    /**
     * Maps a failed Guest mutation POST to the form page it should redirect back to.
     *
     * @param method HTTP method of the failed request
     * @param path application-relative request path (no query string, no context path)
     * @return {@code /guests/new} or {@code /guests/{id}/edit}, or {@code null} for any request
     *     that is not a Guest create/update submission
     */
    static String redirectPathFor(String method, String path) {
        if (!"POST".equalsIgnoreCase(method)) {
            return null;
        }
        if ("/guests".equals(path)) {
            return "/guests/new";
        }
        if (!path.startsWith("/guests/")) {
            return null;
        }
        String id = path.substring("/guests/".length());
        try {
            UUID.fromString(id);
            return "/guests/" + id + "/edit";
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
