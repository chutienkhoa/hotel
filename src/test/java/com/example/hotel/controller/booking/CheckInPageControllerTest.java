package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Check-in MVC authorization and Review-page rendering rules. */
@WebMvcTest(CheckInPageController.class)
@Import(CheckInPageControllerTest.MethodSecurityTestConfiguration.class)
class CheckInPageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN, MANAGER, and STAFF can all access the Check-in landing page. */
    @Test
    void shouldAllowAdminManagerAndStaffToAccessCheckInLanding() throws Exception {
        mockMvc.perform(get("/check-in").with(user("admin").authorities(checkInAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/check-in").with(user("manager").authorities(checkInAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/check-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms a user without CHECK_IN cannot access the Check-in landing page. */
    @Test
    void shouldForbidUserWithoutCheckInPermission() throws Exception {
        mockMvc.perform(get("/check-in").with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Check-in landing page distinguishes its three entry flows without dead links. */
    @Test
    void shouldRenderThreeDistinctEntryFlowsWithoutDeadLinks() throws Exception {
        mockMvc.perform(get("/check-in").with(user("admin").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Existing Reservation")))
                .andExpect(content().string(containsString("OTA Booking Not Entered")))
                .andExpect(content().string(containsString("Walk-in")))
                .andExpect(content().string(containsString("href=\"/check-in/existing\"")))
                .andExpect(content().string(containsString("href=\"/check-in/ota-entry\"")))
                .andExpect(content().string(containsString("href=\"/check-in/walk-in\"")))
                .andExpect(content().string(not(containsString("href=\"#\""))))
                .andExpect(content().string(not(containsString("javascript:void(0)"))));
    }

    /** Confirms an EARLY Review shows the block message and no Confirm Check-in action. */
    @Test
    void shouldRenderEarlyReviewWithoutConfirmAction() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.EARLY, false, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Early check-in is not allowed.")))
                .andExpect(content().string(not(containsString("Confirm Check-in</button>"))));
    }

    /** Confirms a LATE Review shows the warning and the Confirm Check-in action. */
    @Test
    void shouldRenderLateReviewWithWarningAndConfirmAction() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.LATE, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This guest is checking in late.")))
                .andExpect(content().string(containsString("Confirm Check-in</button>")));
    }

    /** Confirms a DIRECT Reservation's Review never renders an OTA reference line. */
    @Test
    void shouldNotRenderOtaReferenceForDirectReview() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Ref:"))));
    }

    /** Confirms an OTA Reservation's Review renders its reference. */
    @Test
    void shouldRenderOtaReferenceForOtaReview() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, "BK-12345"));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ref: BK-12345")));
    }

    /** Confirms the Guest tile shows only the Guest Code, linked, for a user authorized to manage guests. */
    @Test
    void shouldShowOnlyGuestCodeAsLinkWhenAuthorizedToManageGuests() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(checkInAndManageGuestAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(containsString("class=\"reservation-info-value reservation-info-link\"")))
                .andExpect(content().string(containsString("href=\"/guests/" + GUEST_ID + "\"")))
                .andExpect(content().string(not(containsString("Nguyen Van A"))))
                .andExpect(content().string(not(containsString("Vietnam"))))
                .andExpect(content().string(not(containsString("No passport image on file."))))
                .andExpect(content().string(not(containsString("View Passport"))));
    }

    /** Confirms the Guest tile shows the Guest Code as plain text, never a link, without PERM_MANAGE_GUEST. */
    @Test
    void shouldShowGuestCodeAsPlainTextWithoutManageGuestPermission() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(not(containsString("href=\"/guests/" + GUEST_ID + "\""))))
                .andExpect(content().string(not(containsString("reservation-info-link"))))
                .andExpect(content().string(not(containsString("Nguyen Van A"))))
                .andExpect(content().string(not(containsString("Vietnam"))))
                .andExpect(content().string(not(containsString("No passport image on file."))));
    }

    /** Confirms the Check-in confirm POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForConfirmPost() throws Exception {
        mockMvc.perform(post("/check-in/reservations/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the sidebar renders the Check-in link only when the user has CHECK_IN. */
    @Test
    void shouldRenderCheckInSidebarLinkOnlyWithCheckInPermission() throws Exception {
        mockMvc.perform(get("/check-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-check-in\"")));
    }

    /** Builds a representative Check-in Review response. */
    private CheckInReviewResponse reviewResponse(CheckInTiming timing, boolean eligible, String otaReference) {
        return new CheckInReviewResponse(
                RESERVATION_ID,
                "R20260915-000001",
                "CONFIRMED",
                eligible,
                timing,
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 15),
                Instant.parse("2026-09-15T10:00:00Z"),
                otaReference == null ? BookingSource.DIRECT : BookingSource.BOOKING_COM,
                otaReference,
                GUEST_ID,
                "Nguyen Van A",
                "GUEST-001",
                "Vietnam",
                false,
                List.of(),
                java.math.BigDecimal.TEN,
                "VND");
    }

    /** Builds the CHECK_IN authority. */
    private static List<SimpleGrantedAuthority> checkInAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    /** Builds CHECK_IN combined with MANAGE_GUEST. */
    private static List<SimpleGrantedAuthority> checkInAndManageGuestAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_CHECK_IN"), new SimpleGrantedAuthority("PERM_MANAGE_GUEST"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
