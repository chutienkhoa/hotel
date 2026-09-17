package com.example.hotel.controller.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;

/** Verifies oversized multipart requests receive Guest-specific feedback only on Guest form routes. */
class GuestMultipartExceptionHandlerTest {

    private final GuestMultipartExceptionHandler handler = new GuestMultipartExceptionHandler();

    /** Confirms an oversized Create Guest upload returns to the form with the approved message. */
    @Test
    void shouldRedirectOversizedCreateUploadToGuestFormWithFriendlyMessage() {
        MockHttpServletRequest request = request("/guests");
        FlashMap flashMap = flashMap(request);

        Object result = handler.handle(new MaxUploadSizeExceededException(5L * 1024 * 1024), request);

        assertEquals("redirect:/guests/new", result);
        assertEquals("Passport image must not exceed 5 MB.", flashMap.get("errorMessage"));
    }

    /** Confirms an oversized replacement returns to the corresponding Guest edit form. */
    @Test
    void shouldRedirectOversizedReplacementToGuestEditFormWithFriendlyMessage() {
        UUID guestId = UUID.randomUUID();
        MockHttpServletRequest request = request("/guests/" + guestId);
        FlashMap flashMap = flashMap(request);

        Object result = handler.handle(new MaxUploadSizeExceededException(5L * 1024 * 1024), request);

        assertEquals("redirect:/guests/" + guestId + "/edit", result);
        assertEquals("Passport image must not exceed 5 MB.", flashMap.get("errorMessage"));
    }

    /** Confirms unrelated multipart failures do not receive a misleading Guest passport message. */
    @Test
    void shouldKeepNonGuestMultipartFailureAsPayloadTooLarge() {
        MockHttpServletRequest request = request("/additional-revenues");

        Object result = handler.handle(new MaxUploadSizeExceededException(5L * 1024 * 1024), request);

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    }

    /** Creates a POST request that represents a failed multipart form submission. */
    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    /** Registers the output flash map normally supplied by Spring MVC for redirects. */
    private FlashMap flashMap(MockHttpServletRequest request) {
        FlashMap flashMap = new FlashMap();
        request.setAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE, flashMap);
        return flashMap;
    }
}
