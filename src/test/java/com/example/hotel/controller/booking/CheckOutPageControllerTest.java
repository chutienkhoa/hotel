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
@Import({CheckOutPageControllerTest.MethodSecurityTestConfiguration.class, com.example.hotel.config.I18nConfig.class})
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
    private com.example.hotel.service.customer.GuestQueryService guestQueryService;

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

    private CheckOutReviewResponse overdueReview(long days) {
        return new CheckOutReviewResponse(
                RESERVATION_ID, "R20260917-000009", "CHECKED_IN", false, GUEST_ID, "GUEST-001", List.of(),
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 22), Instant.parse("2026-09-17T10:00:00Z"), "READY",
                days, LocalDate.of(2026, 9, 22).plusDays(days));
    }

    /** Confirms an overdue Review shows the notice, the correct days, no confirm action, and Extend Stay when authorized. */
    @Test
    void shouldRenderTheOverdueNoticeWithExtendActionForAuthorizedUsers() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(overdueReview(2));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("manager").authorities(
                        new SimpleGrantedAuthority("PERM_CHECK_OUT"), new SimpleGrantedAuthority("PERM_EXTEND_STAY"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"overdue-departure\"")))
                .andExpect(content().string(containsString("2 days overdue")))
                .andExpect(content().string(containsString("id=\"overdue-extend-stay\"")))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout\""))));
    }

    /** Confirms MANAGE_BOOKING alone (without EXTEND_STAY) does not show the extend action. */
    @Test
    void shouldNotShowTheExtendActionForManageBookingAlone() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(overdueReview(1));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("m").authorities(
                        new SimpleGrantedAuthority("PERM_CHECK_OUT"), new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))))
                .andExpect(content().string(containsString("id=\"overdue-ask-manager\"")))
                .andExpect(content().string(not(containsString("id=\"overdue-extend-stay\""))));
    }

    /** Confirms a user without the extension permission sees the blocked state and the ask-a-manager hint, not the action. */
    @Test
    void shouldHideTheExtendActionWithoutTheExtensionPermission() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(overdueReview(1));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 day overdue")))
                .andExpect(content().string(containsString("id=\"overdue-ask-manager\"")))
                .andExpect(content().string(containsString("Ask a manager or authorized staff member to extend the stay.")))
                .andExpect(content().string(not(containsString("id=\"overdue-extend-stay\""))));
    }

    /** Confirms the overdue notice is localized to Vietnamese. */
    @Test
    void shouldRenderTheOverdueNoticeInVietnamese() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(overdueReview(3));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi"))
                        .with(user("staff").authorities(checkOutAuthority())))
                .andExpect(content().string(containsString("đã quá hạn 3 ngày")));
    }

    /** Confirms an on-time departure shows no overdue notice. */
    @Test
    void shouldNotShowAnOverdueNoticeForAnOnTimeDeparture() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(content().string(not(containsString("id=\"overdue-departure\""))));
    }

    /** Confirms a blocked (not-eligible) Review renders no Confirm action. */
    @Test
    void shouldRenderReviewWithoutConfirmActionWhenNotEligible() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(false, "PAYMENT_REQUIRED"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not ready for checkout")))
                .andExpect(content().string(containsString("Cannot proceed to checkout")))
                .andExpect(content().string(containsString("id=\"confirm-checkout-disabled\"")))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout\""))))
                .andExpect(content().string(not(containsString("/confirm\""))));
    }

    /** Confirms an eligible Review renders the Confirm Check-out action. */
    @Test
    void shouldRenderReviewWithConfirmActionWhenEligible() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"confirm-checkout\"")))
                .andExpect(content().string(containsString("action=\"/check-out/" + RESERVATION_ID + "/confirm\"")))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout-disabled\""))));
    }

    /** Confirms the Guest Information card shows the supported guest fields, and that the page still renders without a profile. */
    @Test
    void shouldShowSupportedGuestFieldsOnReview() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(new com.example.hotel.dto.customer.response.GuestLookupResponse(
                GUEST_ID, "GUEST-001", "Nguyen Van A", "a@example.com", "0901234567", "Vietnam", LocalDate.of(2000, 10, 4), "P0012345"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Vietnam")))
                .andExpect(content().string(containsString("0901234567")))
                .andExpect(content().string(containsString("a@example.com")))
                .andExpect(content().string(containsString("04/10/2000")))
                .andExpect(content().string(containsString("P0012345")));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(null);
        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")));
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

    /** Confirms a successful Confirm delegates to the existing authoritative checkOut and redirects (PRG) to Checkout Complete. */
    @Test
    void shouldDelegateToExistingCheckOutServiceAndRedirectToCheckoutCompleteOnSuccess() throws Exception {
        mockMvc.perform(post("/check-out/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-out/" + RESERVATION_ID + "/complete"));

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

    /** Confirms Check-out is reached through Front Desk: no global Check-out item, Front Desk present. */
    @Test
    void shouldNotRenderGlobalCheckOutSidebarLinkButKeepFrontDesk() throws Exception {
        when(checkOutQueryService.search(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/check-out").with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("nav-check-out"))))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-front-desk\"")));
    }

    /** Confirms the Check-out sidebar link marks itself active while on the Check-out queue page. */
    @Test
    void shouldMarkSidebarActiveOnCheckOutSearchPage() throws Exception {
        when(checkOutQueryService.search(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/check-out").with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"app-shell page-check-out\"")));
    }

    /** Confirms the Check-out sidebar link marks itself active while on the Check-out Review page. */
    @Test
    void shouldMarkSidebarActiveOnCheckOutReviewPage() throws Exception {
        when(checkOutQueryService.review(RESERVATION_ID)).thenReturn(review(true, "READY"));

        mockMvc.perform(get("/check-out/{id}", RESERVATION_ID).with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"app-shell page-check-out\"")));
    }

    /** Builds a representative Check-out list item. */
    private CheckOutListItemResponse listItem() {
        return new CheckOutListItemResponse(
                RESERVATION_ID, "R20260917-000009", GUEST_ID, "GUEST-001", "305, 202", LocalDate.of(2026, 9, 19), "READY", 0);
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
                readiness,
                0,
                LocalDate.of(2026, 9, 19));
    }

    /** Builds the CHECK_OUT authority. */
    private static List<SimpleGrantedAuthority> checkOutAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Confirms an out-of-range Check-out page redirects to the last valid page preserving the room filter and sort. */
    @Test
    void shouldRedirectOutOfRangeCheckOutPagePreservingFilterAndSort() throws Exception {
        org.mockito.Mockito.when(checkOutQueryService.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(), org.springframework.data.domain.PageRequest.of(7, 10), 12));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/check-out")
                        .param("page", "7").param("room", "305").param("sort", "checkOutDate").param("dir", "asc")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("staff").authorities(checkOutAuthority())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/check-out?room=305&sort=checkOutDate&dir=asc&page=1"));
    }
}
