package com.example.hotel.controller.customer;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;

/** Converts oversized Guest passport form uploads into a safe form-level message. */
@ControllerAdvice
public class GuestMultipartExceptionHandler {

    private static final String PASSPORT_SIZE_MESSAGE = "Passport image must not exceed 5 MB.";

    /**
     * Redirects an oversized Guest form upload back to its Create or Edit page with a friendly
     * message. Other MVC multipart uploads retain an ordinary 413 response rather than receiving
     * Guest-specific feedback.
     *
     * @param exception multipart exception raised before controller argument binding
     * @param request current browser request
     * @return Guest-form redirect, or a non-Guest HTTP 413 response
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object handle(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        String redirectPath = guestFormRedirectPath(request);
        if (redirectPath == null) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        FlashMap flashMap = (FlashMap) request.getAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE);
        if (flashMap != null) {
            flashMap.put("errorMessage", PASSPORT_SIZE_MESSAGE);
        }
        return "redirect:" + redirectPath;
    }

    /**
     * Maps only the two Guest mutation URLs to their form pages.
     *
     * @param request browser request that failed multipart parsing
     * @return safe application-relative form path, or {@code null} for non-Guest requests
     */
    private String guestFormRedirectPath(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        String path = applicationPath(request);
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

    /**
     * Removes the optional servlet context path before matching an application route.
     *
     * @param request current browser request
     * @return application-relative path
     */
    private String applicationPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return contextPath == null || contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
    }
}
