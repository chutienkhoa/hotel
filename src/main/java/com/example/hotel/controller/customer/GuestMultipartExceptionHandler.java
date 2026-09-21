package com.example.hotel.controller.customer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;

/**
 * Converts an oversized Guest passport form upload into a safe form-level message when the
 * multipart size failure surfaces during normal Spring MVC dispatch (for example, while Spring
 * resolves the {@code passportImages} controller argument). A failure surfaced earlier — while
 * Spring Security reads the CSRF request parameter from the same multipart body, before dispatch
 * ever begins — never reaches a {@code @ControllerAdvice} and is instead handled by
 * {@link GuestMultipartUploadFilter}. Both share their message and Guest-route mapping through
 * {@link GuestMultipartFormRoutes} so the two paths behave identically.
 */
@ControllerAdvice
public class GuestMultipartExceptionHandler {

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
        String redirectPath = GuestMultipartFormRoutes.redirectPathFor(request.getMethod(), applicationPath(request));
        if (redirectPath == null) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        FlashMap flashMap = (FlashMap) request.getAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE);
        if (flashMap != null) {
            flashMap.put("passportError", GuestMultipartFormRoutes.PASSPORT_SIZE_MESSAGE);
        }
        return "redirect:" + redirectPath;
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
