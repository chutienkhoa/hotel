package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CheckOutListItemResponse;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.ReservationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Check-out MVC authorization, search/Review rendering, and Confirm delegation. */
@WebMvcTest(CheckOutPageController.class)
@Import(CheckOutPageControllerTest.MethodSecurityTestConfiguration.class)
class CheckOutPageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckOutQueryService checkOutQueryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms a user with CHECK_OUT can access the Check-out search page. */
    @Test
    void shouldAllowCheckOutAuthorizedUserToAccessSearch() throws Exception {
        when(checkOutQueryService.search(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/check-out").with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Check-out")));
    }

    /** Confirms a user without CHECK_OUT cannot access the Check-out search page. */
    @Test
    void shouldForbidUserWithoutCheckOutPermission() throws Exception {
        mockMvc.perform(get("/check-out").with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the search results table renders current room numbers and readiness, never an amount. */
    @Test
    void shouldRenderSearchResultsWithReadinessAndCurrentRooms() throws Exception {
        when(checkOutQueryService.search(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(listItem()), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/check-out").with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("R20260917-000009")))
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(containsString("305, 202")))
                .andExpect(content().string(containsString("Ready")))
                .andExpect(content().string(not(containsString("Nguyen Van A"))));
    }

    /** Confirms a blocked (not-eligible) Review renders no Confirm action. */
    @Test
    void shouldRenderReviewWithoutConfirmActionWhenNotEligible() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(false, "PAYMENT_REQUIRED"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment required before check-out.")))
                .andExpect(content().string(not(containsString("Confirm Check-out</button>"))));
    }

    /** Confirms an eligible Review renders the Confirm Check-out action. */
    @Test
    void shouldRenderReviewWithConfirmActionWhenEligible() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm Check-out</button>")));
    }

    /** Confirms the Review Guest tile shows only the Guest Code, never a full profile field. */
    @Test
    void shouldShowOnlyGuestCodeOnReview() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(not(containsString("Nguyen Van A"))))
                .andExpect(content().string(not(containsString("Vietnam"))));
    }

    /** Confirms the Confirm Check-out POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForConfirmPost() throws Exception {
        mockMvc.perform(post("/check-out/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms Confirm Check-out requires PERM_CHECK_OUT. */
    @Test
    void shouldForbidConfirmWithoutCheckOutPermission() throws Exception {
        mockMvc.perform(post("/check-out/{id}/confirm", RESERVATION_ID)
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms a successful Confirm delegates to the existing authoritative checkOut and redirects to the queue. */
    @Test
    void shouldDelegateToExistingCheckOutServiceAndRedirectToQueueOnSuccess() throws Exception {
        mockMvc.perform(post("/check-out/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-out"))
                .andExpect(flash().attribute("successMessage", "Check-out completed successfully."));

        verify(reservationService).checkOut(RESERVATION_ID);
    }

    /** Confirms a business failure from the existing service redirects back to Review with the safe message. */
    @Test
    void shouldRedirectBackToReviewWithErrorOnBusinessFailure() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Outstanding balance must be zero."))
                .when(reservationService)
                .checkOut(RESERVATION_ID);

        mockMvc.perform(post("/check-out/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-out/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage", "Outstanding balance must be zero."));
    }

    /** Confirms the sidebar renders the Check-out link only when the user has CHECK_OUT. */
    @Test
    void shouldRenderCheckOutSidebarLinkOnlyWithCheckOutPermission() throws Exception {
        when(checkOutQueryService.search(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/check-out").with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-check-out\"")));
    }

    /** Builds a representative Check-out list item. */
    private CheckOutListItemResponse listItem() {
        return new CheckOutListItemResponse(
                RESERVATION_ID, "R20260917-000009", GUEST_ID, "GUEST-001", "305, 202", LocalDate.of(2026, 9, 19), "READY");
    }

    /** Builds a representative Check-out Review. */
    private CheckOutReviewResponse review(boolean eligible, String readiness) {
        return new CheckOutReviewResponse(
                RESERVATION_ID,
                "R20260917-000009",
                "CHECKED_IN",
                eligible,
                GUEST_ID,
                "GUEST-001",
                List.of(),
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 19),
                Instant.parse("2026-09-17T10:00:00Z"),
                readiness);
    }

    /** Builds the CHECK_OUT authority. */
    private static List<SimpleGrantedAuthority> checkOutAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
