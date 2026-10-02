package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
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
import org.springframework.mock.web.MockHttpSession;
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
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

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

    /** Confirms the Walk-in and OTA entry forms offer VND as the only Reservation currency. */
    @Test
    void shouldOfferOnlyVndAsReservationCurrencyOnWalkInAndOtaEntry() throws Exception {
        for (String path : List.of("/check-in/walk-in", "/check-in/ota-entry")) {
            mockMvc.perform(get(path).with(user("staff").authorities(checkInAuthority())))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("value=\"VND\">VND")))
                    .andExpect(content().string(not(containsString("value=\"USD\""))));
        }
    }

    /** Confirms a crafted USD Walk-in review/confirm is rejected by validation and never reaches the service. */
    @Test
    void shouldRejectUsdWalkInBeforeReachingTheService() throws Exception {
        for (String path : List.of("/check-in/walk-in/review", "/check-in/walk-in/confirm")) {
            mockMvc.perform(walkInPost(path, "USD"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Reservation currency must be VND.")));
        }
        verify(checkInService, never()).reviewWalkIn(any());
        verify(checkInService, never()).confirmWalkIn(any());
    }

    /** Confirms a VND Walk-in passes validation and is handed to the service. */
    @Test
    void shouldAcceptVndWalkIn() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(checkInService.confirmWalkIn(any())).thenReturn(
                new com.example.hotel.dto.booking.response.Response(
                        reservationId, "R1", "CHECKED_IN", java.math.BigDecimal.TEN, "VND"));

        mockMvc.perform(walkInPost("/check-in/walk-in/confirm", "VND"))
                .andExpect(status().is3xxRedirection());
        verify(checkInService).confirmWalkIn(any());
    }

    /** Confirms a crafted USD OTA entry is rejected by validation and never reaches the service. */
    @Test
    void shouldRejectUsdOtaEntryBeforeReachingTheService() throws Exception {
        mockMvc.perform(otaEntryPost("USD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reservation currency must be VND.")));
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms a VND OTA entry passes validation and is handed to the service. */
    @Test
    void shouldAcceptVndOtaEntry() throws Exception {
        when(checkInService.createOtaEntry(any())).thenReturn(
                new com.example.hotel.dto.booking.response.Response(
                        UUID.randomUUID(), "R1", "CONFIRMED", java.math.BigDecimal.TEN, "VND"));

        mockMvc.perform(otaEntryPost("VND")).andExpect(status().is3xxRedirection());
        verify(checkInService).createOtaEntry(any());
    }

    /** Confirms a valid {@code createdGuestId} flash attribute pre-selects the Guest on the Walk-in form. */
    @Test
    void shouldPreSelectCreatedGuestOnWalkInFormWhenReturningFromGuestCreation() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        mockMvc.perform(get("/check-in/walk-in")
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(selectedGuestOptionFragment())));
    }

    /** Confirms a valid {@code createdGuestId} flash attribute pre-selects the Guest on the OTA entry form. */
    @Test
    void shouldPreSelectCreatedGuestOnOtaEntryFormWhenReturningFromGuestCreation() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        mockMvc.perform(get("/check-in/ota-entry")
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(selectedGuestOptionFragment())));
    }

    /**
     * Confirms a {@code createdGuestId} that no longer resolves to a real Guest (missing/invalid)
     * is safely ignored: the Walk-in form still loads with nothing pre-selected.
     */
    @Test
    void shouldIgnoreUnknownCreatedGuestIdOnWalkInForm() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(any())).thenReturn(null);

        mockMvc.perform(get("/check-in/walk-in")
                        .flashAttr("createdGuestId", UUID.randomUUID())
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("selected=\"selected\""))));
    }

    /** Confirms Walk-in Review -&gt; Back redisplays the form with every previously entered value intact (spec 9.3.3a). */
    @Test
    void shouldPreserveWalkInFieldsOnBackFromReview() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/check-in/walk-in/back")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkOutDate", LocalDate.now().plusDays(3).toString())
                        .param("adultCount", "2")
                        .param("childCount", "1")
                        .param("currency", "VND")
                        .param("notes", "Late arrival expected")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "1200000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(selectedGuestOptionFragment())))
                .andExpect(content().string(containsString("Late arrival expected")));
        verify(checkInService, never()).confirmWalkIn(any());
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms the Walk-in Back action requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForWalkInBack() throws Exception {
        mockMvc.perform(post("/check-in/walk-in/back").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms the rendered Walk-in Review page wires its "Back to Walk-in" control to resubmit the
     * reviewed selections to the new Back endpoint (spec 9.3.3a), rather than a plain link that
     * would discard them on a blank GET.
     */
    @Test
    void shouldRenderWalkInReviewBackControlWiredToPreserveState() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(new com.example.hotel.dto.booking.response.WalkInReviewResponse(
                GUEST_ID, "Ann Lee", "G000001", false, null,
                LocalDate.now(), LocalDate.now().plusDays(2), Instant.now(),
                List.of(), java.math.BigDecimal.TEN, "VND"));

        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("form=\"walk-in-confirm-form\"")))
                .andExpect(content().string(containsString("formaction=\"/check-in/walk-in/back\"")))
                .andExpect(content().string(containsString("id=\"walk-in-confirm-form\"")));
    }

    /**
     * Confirms the Walk-in "+ Create New Guest" stash-and-redirect preserves the rest of the
     * in-progress wizard state across the full Guest-creation round trip, and that the created
     * Guest is pre-selected on return (spec 9.2.5 / 9.3.3a). No Reservation or Guest is created by
     * either request.
     */
    @Test
    void shouldStashAndRestoreWalkInFieldsAcrossCreateNewGuestRoundTrip() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/check-in/walk-in/new-guest")
                        .session(session)
                        .param("checkOutDate", LocalDate.now().plusDays(4).toString())
                        .param("adultCount", "3")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .param("notes", "Needs late checkout")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "900000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/guests/new?returnTo=/check-in/walk-in"));

        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        mockMvc.perform(get("/check-in/walk-in")
                        .session(session)
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(selectedGuestOptionFragment())))
                .andExpect(content().string(containsString("Needs late checkout")));
        verify(checkInService, never()).confirmWalkIn(any());
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms the same stash-and-restore round trip for OTA Booking Not Entered. */
    @Test
    void shouldStashAndRestoreOtaEntryFieldsAcrossCreateNewGuestRoundTrip() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/check-in/ota-entry/new-guest")
                        .session(session)
                        .param("checkInDate", LocalDate.now().toString())
                        .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                        .param("adultCount", "2")
                        .param("childCount", "0")
                        .param("source", "AGODA")
                        .param("otaBookingReference", "AG-99")
                        .param("currency", "VND")
                        .param("notes", "OTA only note")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "700000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/guests/new?returnTo=/check-in/ota-entry"));

        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        mockMvc.perform(get("/check-in/ota-entry")
                        .session(session)
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(selectedGuestOptionFragment())))
                .andExpect(content().string(containsString("AG-99")))
                .andExpect(content().string(containsString("OTA only note")));
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms Walk-in stashed state never leaks into the OTA entry form, and vice versa. */
    @Test
    void shouldKeepWalkInAndOtaEntryStashedStateIsolated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/check-in/walk-in/new-guest")
                        .session(session)
                        .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                        .param("adultCount", "2")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .param("notes", "Walk-in only note")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "500000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/check-in/ota-entry")
                        .session(session)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Walk-in only note"))));
    }

    /**
     * Confirms a successful Walk-in confirmation clears any leftover stashed state, so it can
     * never resurface on a later, unrelated Walk-in visit.
     */
    @Test
    void shouldClearStashedWalkInStateAfterSuccessfulConfirm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(post("/check-in/walk-in/new-guest")
                        .session(session)
                        .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                        .param("adultCount", "2")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .param("notes", "Stale note")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "500000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        when(checkInService.confirmWalkIn(any())).thenReturn(new com.example.hotel.dto.booking.response.Response(
                UUID.randomUUID(), "R1", "CHECKED_IN", java.math.BigDecimal.TEN, "VND"));
        mockMvc.perform(walkInPost("/check-in/walk-in/confirm", "VND").session(session))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/check-in/walk-in").session(session).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Stale note"))));
    }

    /** Confirms the new Walk-in/OTA Create-New-Guest wiring stays behind the existing CHECK_IN authorization. */
    @Test
    void shouldForbidNewGuestAndBackActionsWithoutCheckInPermission() throws Exception {
        var noPermission = List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));

        mockMvc.perform(post("/check-in/walk-in/back").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/walk-in/new-guest").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/ota-entry/new-guest").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Builds a representative created-Guest lookup entry matching {@code GUEST_ID}. */
    private GuestLookupResponse guestLookup() {
        return new GuestLookupResponse(GUEST_ID, "G000001", "Ann Lee", null, null, null);
    }

    /**
     * Builds the exact rendered fragment of the {@link #guestLookup()} entry's {@code <option>}
     * when Thymeleaf marks it selected, matching the actual attribute order the guest selector
     * renders in (value, then the {@code data-*} verification attributes, then {@code selected}).
     *
     * @return the selected-option markup fragment to search for
     */
    private String selectedGuestOptionFragment() {
        return "value=\"" + GUEST_ID + "\" data-guest-code=\"G000001\" data-full-name=\"Ann Lee\" selected=\"selected\"";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder walkInPost(
            String path, String currency) {
        return post(path)
                .param("guestId", GUEST_ID.toString())
                .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                .param("adultCount", "2")
                .param("childCount", "0")
                .param("currency", currency)
                .param("rooms[0].roomId", UUID.randomUUID().toString())
                .param("rooms[0].nightlyRate", "1000000")
                .with(user("staff").authorities(checkInAuthority()))
                .with(csrf());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder otaEntryPost(
            String currency) {
        return post("/check-in/ota-entry")
                .param("guestId", GUEST_ID.toString())
                .param("checkInDate", LocalDate.now().toString())
                .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                .param("adultCount", "2")
                .param("childCount", "0")
                .param("source", "AGODA")
                .param("otaBookingReference", "AG-1")
                .param("currency", currency)
                .param("rooms[0].roomId", UUID.randomUUID().toString())
                .param("rooms[0].nightlyRate", "1000000")
                .with(user("staff").authorities(checkInAuthority()))
                .with(csrf());
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
                "VND",
                new com.example.hotel.dto.booking.response.ArrivalReadiness(
                        eligible ? com.example.hotel.dto.booking.response.ArrivalReadinessState.READY
                                : com.example.hotel.dto.booking.response.ArrivalReadinessState.NEEDS_ATTENTION,
                        timing, List.of()),
                1,
                0,
                List.of());
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
