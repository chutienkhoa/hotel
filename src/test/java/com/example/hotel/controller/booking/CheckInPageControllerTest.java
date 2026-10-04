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

import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.FrontDeskQueryService;
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
@WebMvcTest(value = CheckInPageController.class, properties = "hotel.i18n.default-locale=en")
@Import({CheckInPageControllerTest.MethodSecurityTestConfiguration.class, com.example.hotel.config.I18nConfig.class})
class CheckInPageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GUEST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private com.example.hotel.service.room.RoomImageService roomImageService;

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

    /** Confirms the landing lists real arrivals with Today/overdue text, status badges and Select links to Review. */
    @Test
    void shouldRenderRecentConfirmedReservationsFromTheArrivalsReadModel() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 2);
        ArrivalReadiness ready = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of());
        ArrivalReadiness late = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.LATE, List.of());
        FrontDeskRoomResponse room = new FrontDeskRoomResponse(UUID.randomUUID(), "DEMO-101", "Single Room", null);
        when(frontDeskQueryService.hotelToday()).thenReturn(today);
        when(frontDeskQueryService.recentArrivals(5, null, null)).thenReturn(List.of(
                new FrontDeskArrivalRow(RESERVATION_ID, "R20261002-000001", "Nguyen Van Minh", "DEMO-G001",
                        BookingSource.DIRECT, null, today.minusDays(1), true, true, late, List.of(room), false, null, 3, 1),
                new FrontDeskArrivalRow(GUEST_ID, "R20261002-000002", "Tran Thi Mai", "DEMO-G002",
                        BookingSource.AGODA, null, today, false, false, ready, List.of(room), false, null, 3, 0)));

        mockMvc.perform(get("/check-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Recent Confirmed Reservations")))
                .andExpect(content().string(containsString("R20261002-000001")))
                .andExpect(content().string(containsString("DEMO-G001")))
                .andExpect(content().string(containsString("1 day overdue")))
                .andExpect(content().string(containsString("Today")))
                .andExpect(content().string(containsString("Ready for Check-in")))
                .andExpect(content().string(containsString("Needs attention")))
                .andExpect(content().string(containsString("Agoda")))
                .andExpect(content().string(containsString("href=\"/check-in/reservations/" + RESERVATION_ID + "\"")))
                .andExpect(content().string(containsString("href=\"/front-desk?view=arrivals\"")))
                .andExpect(content().string(containsString("href=\"/front-desk\"")));
    }

    /** Confirms every data column of the recent list is a sortable header, Action is not, and links toggle direction. */
    @Test
    void shouldRenderSortableHeadersExceptAction() throws Exception {
        when(frontDeskQueryService.hotelToday()).thenReturn(LocalDate.of(2026, 10, 2));
        when(frontDeskQueryService.recentArrivals(5, "checkInDate", "asc")).thenReturn(List.of(
                new FrontDeskArrivalRow(RESERVATION_ID, "R-1", "Ann", "G-1", BookingSource.DIRECT, null,
                        LocalDate.of(2026, 10, 2), false, false,
                        new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of()),
                        List.of(), false, null, 2, 0)));

        mockMvc.perform(get("/check-in").param("sort", "checkInDate").param("dir", "asc")
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/check-in?sort=reservationNumber&amp;dir=asc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=guestName&amp;dir=asc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=checkInDate&amp;dir=desc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=room&amp;dir=asc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=nights&amp;dir=asc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=source&amp;dir=asc\"")))
                .andExpect(content().string(containsString("href=\"/check-in?sort=status&amp;dir=asc\"")))
                .andExpect(content().string(containsString("aria-sort=\"ascending\"")))
                .andExpect(content().string(not(containsString("sort=action"))));
    }

    /** Confirms sorting is language-independent: the Vietnamese UI uses the same sort keys. */
    @Test
    void shouldUseSameSortKeysInVietnamese() throws Exception {
        when(frontDeskQueryService.hotelToday()).thenReturn(LocalDate.of(2026, 10, 2));
        when(frontDeskQueryService.recentArrivals(5, "nights", "desc")).thenReturn(List.of(
                new FrontDeskArrivalRow(RESERVATION_ID, "R-1", "Ann", "G-1", BookingSource.DIRECT, null,
                        LocalDate.of(2026, 10, 2), false, false,
                        new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of()),
                        List.of(), false, null, 2, 0)));

        mockMvc.perform(get("/check-in").param("sort", "nights").param("dir", "desc")
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi"))
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/check-in?sort=nights&amp;dir=asc\"")))
                .andExpect(content().string(containsString("aria-sort=\"descending\"")));
    }

    /** Confirms the overdue line is pluralised. */
    @Test
    void shouldPluraliseOverdueDays() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 2);
        ArrivalReadiness late = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.LATE, List.of());
        when(frontDeskQueryService.hotelToday()).thenReturn(today);
        when(frontDeskQueryService.recentArrivals(5, null, null)).thenReturn(List.of(
                new FrontDeskArrivalRow(RESERVATION_ID, "R-1", "Ann", "G-1", BookingSource.DIRECT, null,
                        today.minusDays(4), true, true, late, List.of(), false, null, 2, 4)));

        mockMvc.perform(get("/check-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("4 days overdue")));
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

    /**
     * Confirms the Guest Information card shows the Guest's name/nationality (already present on the
     * read model) and the Guest Code link opens Guest Detail for a user authorized to manage guests.
     * Task 33 final layout (check-in-review-final.png) surfaces Guest name/nationality directly,
     * consistent with Guest identity already being shown by full name elsewhere in Front Desk
     * (front-desk/fragments.html); only the Guest Detail navigation link itself stays
     * permission-gated, as it was before this redesign.
     */
    @Test
    void shouldShowOnlyGuestCodeAsLinkWhenAuthorizedToManageGuests() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(checkInAndManageGuestAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(containsString("class=\"reservation-info-value reservation-info-link\"")))
                .andExpect(content().string(containsString("href=\"/guests/" + GUEST_ID + "\"")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Vietnam")))
                .andExpect(content().string(containsString("No passport image on file.")))
                .andExpect(content().string(not(containsString("View Passport"))));
    }

    /**
     * Confirms the Guest Code stays plain text (never a link) without PERM_MANAGE_GUEST, while the
     * Guest Information card's name/nationality still render — those fields are not part of the
     * Guest-management authorization boundary, only the Guest Detail navigation link is.
     */
    @Test
    void shouldShowGuestCodeAsPlainTextWithoutManageGuestPermission() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(not(containsString("href=\"/guests/" + GUEST_ID + "\""))))
                .andExpect(content().string(not(containsString("reservation-info-link"))))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Vietnam")))
                .andExpect(content().string(containsString("No passport image on file.")));
    }

    /** Confirms View Passport renders, using the secure document route, only when a passport image exists. */
    @Test
    void shouldRenderViewPassportLinkOnlyWhenPassportAvailable() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(checkInService.review(RESERVATION_ID)).thenReturn(
                reviewResponseWithPassport(CheckInTiming.NORMAL, true, documentId));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("View Passport")))
                .andExpect(content().string(containsString(
                        "href=\"/guests/" + GUEST_ID + "/documents/" + documentId + "/passport\"")))
                .andExpect(content().string(not(containsString("No passport image on file."))));
    }

    /** Confirms no generic Edit Guest, Upload Passport, or generic Change Room control is ever rendered (spec 9.2.6). */
    @Test
    void shouldNotRenderUnsupportedGuestOrRoomControls() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(reviewResponse(CheckInTiming.NORMAL, true, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(checkInAndManageGuestAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Edit Guest"))))
                .andExpect(content().string(not(containsString("Upload Passport"))))
                .andExpect(content().string(not(containsString(">Change Room<"))));
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

    /** Confirms the OTA entry form has no Currency selector: VND is fixed and USD is never offered. */
    @Test
    void shouldFixVndWithoutCurrencySelectorOnOtaEntry() throws Exception {
        mockMvc.perform(get("/check-in/ota-entry").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"currency\" type=\"hidden\" value=\"VND\"")))
                .andExpect(content().string(not(containsString("id=\"currency\""))))
                .andExpect(content().string(not(containsString("<option value=\"VND\""))))
                .andExpect(content().string(not(containsString("USD"))));
    }

    /**
     * Confirms the Walk-in form fixes the currency to VND and the source to Direct: no currency or source choice is
     * rendered, and no OTA source or OTA booking reference is offered.
     */
    @Test
    void shouldRenderWalkInAsDirectAndVndOnly() throws Exception {
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>Walk-in Reservation</h1>")))
                .andExpect(content().string(containsString("name=\"currency\" type=\"hidden\" value=\"VND\"")))
                .andExpect(content().string(containsString("Direct (Walk-in)")))
                .andExpect(content().string(not(containsString("value=\"USD\""))))
                .andExpect(content().string(not(containsString("name=\"source\""))))
                .andExpect(content().string(not(containsString("AGODA"))))
                .andExpect(content().string(not(containsString("BOOKING_COM"))))
                .andExpect(content().string(not(containsString("AIRBNB"))))
                .andExpect(content().string(not(containsString("otaBookingReference"))))
                .andExpect(content().string(containsString("formaction=\"/check-in/walk-in/new-guest\"")))
                .andExpect(content().string(containsString("action=\"/check-in/walk-in/review\"")));
    }

    /**
     * Confirms the Booking Source card shows Direct as the selected source and the OTA sources as disabled text:
     * none of them is a form control, so no OTA value can be submitted or enabled.
     */
    @Test
    void shouldRenderOtaSourcesAsDisabledNonSubmittableRows() throws Exception {
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("walk-in-source-option--selected")))
                .andExpect(content().string(containsString("aria-disabled=\"true\"")))
                .andExpect(content().string(containsString(">Agoda<")))
                .andExpect(content().string(containsString(">Booking.com<")))
                .andExpect(content().string(containsString(">Airbnb<")))
                .andExpect(content().string(not(containsString("type=\"radio\""))))
                .andExpect(content().string(not(containsString("name=\"source\""))))
                .andExpect(content().string(containsString("href=\"/check-in/ota-entry\"")));
    }

    /** Confirms the Guest Information card offers a real Search Existing Guest control and the identity detail fields. */
    @Test
    void shouldRenderGuestSearchAndIdentityDetailFields() throws Exception {
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Search Existing Guest")))
                .andExpect(content().string(containsString("data-guest-search-toggle")))
                .andExpect(content().string(containsString("data-guest-id-document")))
                .andExpect(content().string(containsString("data-guest-dob")))
                .andExpect(content().string(containsString("ID / Passport Number")))
                .andExpect(content().string(containsString("Date of Birth")))
                .andExpect(content().string(containsString("data-check-availability")));
    }

    /** Confirms the selected Guest's identity values come from the Guest read model and are exposed on the option. */
    @Test
    void shouldExposeGuestIdentityValuesFromTheGuestReadModel() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(
                new GuestLookupResponse(GUEST_ID, "G000001", "Ann Lee", "ann@example.com", "0123", "Japan",
                        LocalDate.of(1990, 8, 15), "C1234567")));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        mockMvc.perform(get("/check-in/walk-in")
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-date-of-birth=\"15/08/1990\"")))
                .andExpect(content().string(containsString("data-id-document-number=\"C1234567\"")));
    }

    /** Confirms the nationality flag is derived from the Guest's nationality, and is omitted when it cannot be mapped. */
    @Test
    void shouldDeriveNationalityFlagFromTheGuestNationality() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(
                new GuestLookupResponse(GUEST_ID, "G000001", "Ann Lee", null, null, "Vietnamese"),
                new GuestLookupResponse(UUID.randomUUID(), "G000002", "Taro", null, null, "Japan"),
                new GuestLookupResponse(UUID.randomUUID(), "G000003", "Zed", null, null, "Atlantis"),
                new GuestLookupResponse(UUID.randomUUID(), "G000004", "Nil", null, null, null)));

        String html = mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-nationality=\"Vietnamese\" data-nationality-flag=\"🇻🇳\"")))
                .andExpect(content().string(containsString("data-nationality=\"Japan\" data-nationality-flag=\"🇯🇵\"")))
                .andExpect(content().string(containsString("data-guest-flag")))
                .andReturn().getResponse().getContentAsString();
        // Unmappable or missing nationality: no flag attribute at all (never "null").
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-nationality=\"Atlantis\">") 
                || html.contains("data-nationality=\"Atlantis\" "));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("data-nationality-flag=\"null\""));
        org.junit.jupiter.api.Assertions.assertEquals(2, html.split("data-nationality-flag=", -1).length - 1);
    }

    /** Confirms the Walk-in form renders in Vietnamese through the message bundle, with no hardcoded English labels. */
    @Test
    void shouldRenderWalkInInVietnamese() throws Exception {
        mockMvc.perform(get("/check-in/walk-in")
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi"))
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đặt phòng khách vãng lai")))
                .andExpect(content().string(containsString("Tạo khách mới")))
                .andExpect(content().string(containsString("Tiếp theo: Tóm tắt đặt phòng")))
                .andExpect(content().string(not(containsString(">Create New Guest<"))));
    }

    /** Confirms the Walk-in room list endpoint returns the service's room options (type and adult capacity). */
    @Test
    void shouldReturnWalkInRoomOptionsFromTheService() throws Exception {
        UUID roomId = UUID.randomUUID();
        LocalDate checkOut = LocalDate.now().plusDays(2);
        when(checkInService.walkInRoomOptions(checkOut)).thenReturn(List.of(
                new com.example.hotel.dto.booking.response.WalkInRoomOption(roomId, "101", "Double Room", 2, "AVAILABLE")));

        mockMvc.perform(get("/check-in/walk-in/available-rooms")
                        .param("checkOutDate", checkOut.toString())
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"roomNumber\":\"101\"")))
                .andExpect(content().string(containsString("\"roomTypeName\":\"Double Room\"")))
                .andExpect(content().string(containsString("\"adultCapacity\":2")));
    }

    /** Confirms a Walk-in capacity rejection is shown next to the Adults field, localized, with the entries kept. */
    @Test
    void shouldShowWalkInCapacityErrorNextToAdultsAndKeepEntries() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenThrow(new com.example.hotel.exception.WalkInReviewException(
                com.example.hotel.exception.WalkInReviewException.Reason.INSUFFICIENT_ADULT_CAPACITY,
                "capacity", 2, 1));

        submitAndFollow(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Insufficient room capacity: 2 adults, but the selected rooms support only 1 adults.")))
                .andExpect(content().string(containsString("aria-describedby=\"adultCount-error\"")))
                .andExpect(content().string(containsString("id=\"adultCount-error\"")))
                .andExpect(content().string(containsString("Please correct the highlighted fields.")));
    }

    /** Confirms a room that is no longer available is rejected on the Rooms section instead of reaching the Summary. */
    @Test
    void shouldShowWalkInRoomUnavailableErrorOnTheRoomSection() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenThrow(new com.example.hotel.exception.WalkInReviewException(
                com.example.hotel.exception.WalkInReviewException.Reason.ROOM_UNAVAILABLE, "unavailable", "DEMO-101"));

        submitAndFollow(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room DEMO-101 is not available for the whole stay.")))
                .andExpect(content().string(containsString("id=\"rooms-error\"")));
    }

    /** Confirms missing Guest, check-out and rooms are reported next to their own fields without calling the service. */
    @Test
    void shouldReportMissingWalkInFieldsNextToTheirFields() throws Exception {
        submitAndFollow(post("/check-in/walk-in/review")
                        .param("adultCount", "1")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Select a guest.")))
                .andExpect(content().string(containsString("Check-out date is required.")))
                .andExpect(content().string(containsString("Select at least one room.")))
                .andExpect(content().string(containsString("id=\"guestId-error\"")))
                .andExpect(content().string(containsString("id=\"checkOutDate-error\"")));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms an invalid (non-positive) nightly rate is rejected with a localized message and never reaches the service. */
    @Test
    void shouldRejectNonPositiveWalkInNightlyRate() throws Exception {
        submitAndFollow(post("/check-in/walk-in/review")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                        .param("adultCount", "2")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .param("rooms[0].roomId", UUID.randomUUID().toString())
                        .param("rooms[0].nightlyRate", "0")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter a nightly rate greater than 0.")));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms the Summary lists the real room lines and VND totals with Direct as the source. */
    @Test
    void shouldRenderWalkInSummaryRoomLinesAndVndTotals() throws Exception {
        LocalDate today = LocalDate.now();
        when(checkInService.reviewWalkIn(any())).thenReturn(new com.example.hotel.dto.booking.response.WalkInReviewResponse(
                GUEST_ID, "Ann Lee", "G000001", false, null, today, today.plusDays(2), Instant.now(),
                List.of(new com.example.hotel.dto.booking.response.CheckInRoomLine(
                        ROOM_ID, "101", "Double Room", today, today.plusDays(2), new java.math.BigDecimal("1200000"), 2,
                        new java.math.BigDecimal("2400000"), 2, "AVAILABLE")),
                new java.math.BigDecimal("2400000"), "VND"));

        summaryPage(checkInAuthority())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reservation Summary")))
                .andExpect(content().string(containsString("Direct (Walk-in)")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("Price Summary (VND)")))
                .andExpect(content().string(containsString("Room 101 (2 nights × 1,200,000)")))
                .andExpect(content().string(containsString("2,400,000")))
                .andExpect(content().string(containsString("Confirm &amp; Check-in")));
        verify(checkInService, never()).confirmWalkIn(any());
    }

    /** Confirms a selection that went stale after the Summary is rejected on the form and nothing is created. */
    @Test
    void shouldNotConfirmWalkInWhenTheSelectionIsNoLongerValid() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenThrow(new com.example.hotel.exception.WalkInReviewException(
                com.example.hotel.exception.WalkInReviewException.Reason.ROOM_UNAVAILABLE, "unavailable", "DEMO-101"));

        submitAndFollow(walkInPost("/check-in/walk-in/confirm", "VND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room DEMO-101 is not available for the whole stay.")));
        verify(checkInService, never()).confirmWalkIn(any());
    }

    /** Confirms a crafted USD Walk-in review/confirm is rejected by validation and never reaches the service. */
    @Test
    void shouldRejectUsdWalkInBeforeReachingTheService() throws Exception {
        for (String path : List.of("/check-in/walk-in/review", "/check-in/walk-in/confirm")) {
            submitAndFollow(walkInPost(path, "USD"))
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

    /** Confirms a crafted USD OTA entry is rejected by validation, redirected back to the form, and never reaches the service. */
    @Test
    void shouldRejectUsdOtaEntryBeforeReachingTheService() throws Exception {
        mockMvc.perform(otaEntryPost("USD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"));
        submitAndFollow(otaEntryPost("USD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reservation currency must be VND.")));
        verify(checkInService, never()).createOtaEntry(any());
        verify(checkInService, never()).reviewOtaEntry(any());
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
    void shouldForbidNewGuestAndSummaryActionsWithoutCheckInPermission() throws Exception {
        var noPermission = List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));

        mockMvc.perform(get("/check-in/walk-in/review").with(user("viewer").authorities(noPermission)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/walk-in/review").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/walk-in/new-guest").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/ota-entry/new-guest").with(user("viewer").authorities(noPermission)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- POST / Redirect / GET contract of the Walk-in wizard -------------------------------------------------

    /** Posts a request, then performs the GET it redirects to with the same session and flash attributes. */
    private org.springframework.test.web.servlet.ResultActions submitAndFollow(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return submitAndFollow(request, checkInAuthority());
    }

    private org.springframework.test.web.servlet.ResultActions submitAndFollow(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            List<SimpleGrantedAuthority> authorities) throws Exception {
        MockHttpSession session = new MockHttpSession();
        org.springframework.test.web.servlet.MvcResult posted = mockMvc.perform(request.session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = posted.getResponse().getRedirectedUrl();
        org.junit.jupiter.api.Assertions.assertNotNull(location);
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder follow =
                get(location).session(session).with(user("staff").authorities(authorities));
        if (!posted.getFlashMap().isEmpty()) {
            follow.flashAttrs(posted.getFlashMap());
        }
        return mockMvc.perform(follow);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getAs(String path, MockHttpSession session) {
        return get(path).session(session).with(user("staff").authorities(checkInAuthority()));
    }

    /** Confirms the POST that prepares the Summary redirects to a GET Summary and does not render it itself. */
    @Test
    void shouldRedirectWalkInReviewPostToAGetSummary() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in/review"))
                .andExpect(content().string(not(containsString("Reservation Summary"))));
        verify(checkInService, never()).confirmWalkIn(any());
    }

    /** Confirms the GET Summary renders from the prepared state and can be refreshed repeatedly without side effects. */
    @Test
    void shouldRenderSummaryFromPreparedStateAndSurviveRefreshBackAndForward() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND").param("notes", "Late arrival expected").session(session))
                .andExpect(status().is3xxRedirection());

        // Summary, refresh, browser Back to the form, browser Forward to the Summary, refresh again.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(getAs("/check-in/walk-in/review", session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Reservation Summary")))
                    .andExpect(content().string(containsString("Confirm &amp; Check-in")));
            mockMvc.perform(getAs("/check-in/walk-in", session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(selectedGuestOptionFragment())))
                    .andExpect(content().string(containsString("Late arrival expected")));
        }
        mockMvc.perform(getAs("/check-in/walk-in/review", session)).andExpect(status().isOk());
        verify(checkInService, never()).confirmWalkIn(any());
    }

    /** Confirms the Summary's Back and Edit controls are plain GET links to the Walk-in form, never a POST. */
    @Test
    void shouldRenderSummaryBackAsAPlainGetLink() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        String html = submitAndFollow(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<a class=\"back-link back-link--primary\" href=\"/check-in/walk-in\">"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"button button-secondary walk-in-edit\" href=\"/check-in/walk-in\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("walk-in/back"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"walk-in-confirm-form\""));
    }

    /** Confirms a Summary requested without prepared state redirects to the Walk-in form with a notice, never a 500. */
    @Test
    void shouldRedirectSummaryToWalkInWhenThereIsNoPreparedState() throws Exception {
        MockHttpSession session = new MockHttpSession();
        org.springframework.test.web.servlet.MvcResult redirected = mockMvc.perform(getAs("/check-in/walk-in/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in"))
                .andReturn();

        mockMvc.perform(getAs("/check-in/walk-in", session).flashAttrs(redirected.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Walk-in summary is no longer available.")));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms an incomplete prepared form (for example one kept for Create New Guest) never reaches the Summary. */
    @Test
    void shouldNotShowSummaryForAnIncompletePreparedForm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/check-in/walk-in/new-guest").session(session)
                        .param("adultCount", "2").param("childCount", "0").param("currency", "VND")
                        .with(user("staff").authorities(checkInAuthority())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(getAs("/check-in/walk-in/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in"));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms a prepared form that became invalid (room taken) is sent back to the form with the error, not a 500. */
    @Test
    void shouldReturnToWalkInWithTheErrorWhenThePreparedSelectionWentStale() throws Exception {
        when(checkInService.reviewWalkIn(any()))
                .thenReturn(walkInReview())
                .thenThrow(new com.example.hotel.exception.WalkInReviewException(
                        com.example.hotel.exception.WalkInReviewException.Reason.ROOM_UNAVAILABLE, "gone", "DEMO-101"));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND").session(session)).andExpect(status().is3xxRedirection());

        org.springframework.test.web.servlet.MvcResult stale = mockMvc.perform(getAs("/check-in/walk-in/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in"))
                .andReturn();
        mockMvc.perform(getAs("/check-in/walk-in", session).flashAttrs(stale.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room DEMO-101 is not available for the whole stay.")));
    }

    /** Confirms a rejected submission is redirected back to the form (never rendered from the POST), values and errors kept. */
    @Test
    void shouldRedirectInvalidWalkInSubmissionBackToTheFormWithItsValues() throws Exception {
        MockHttpSession session = new MockHttpSession();
        org.springframework.test.web.servlet.MvcResult posted = mockMvc.perform(post("/check-in/walk-in/review").session(session)
                        .param("adultCount", "2").param("childCount", "0").param("currency", "VND")
                        .param("notes", "Keep me")
                        .with(user("staff").authorities(checkInAuthority())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in"))
                .andReturn();

        mockMvc.perform(getAs("/check-in/walk-in", session).flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Select a guest.")))
                .andExpect(content().string(containsString("Keep me")));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms the prepared form survives viewing the Summary and Create New Guest, and is removed only by completion. */
    @Test
    void shouldKeepPreparedStateUntilTheWalkInIsCreated() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        when(checkInService.confirmWalkIn(any())).thenReturn(new com.example.hotel.dto.booking.response.Response(
                UUID.randomUUID(), "R1", "CHECKED_IN", java.math.BigDecimal.TEN, "VND"));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND").session(session)).andExpect(status().is3xxRedirection());
        mockMvc.perform(getAs("/check-in/walk-in/review", session)).andExpect(status().isOk());

        // The Create New Guest round trip keeps working on top of the same state.
        mockMvc.perform(post("/check-in/walk-in/new-guest").session(session)
                        .param("checkOutDate", LocalDate.now().plusDays(4).toString())
                        .param("adultCount", "3").param("childCount", "0").param("currency", "VND")
                        .param("notes", "Needs late checkout")
                        .param("rooms[0].roomId", UUID.randomUUID().toString()).param("rooms[0].nightlyRate", "900000")
                        .with(user("staff").authorities(checkInAuthority())).with(csrf()))
                .andExpect(redirectedUrl("/guests/new?returnTo=/check-in/walk-in"));
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());
        mockMvc.perform(getAs("/check-in/walk-in", session).flashAttr("createdGuestId", GUEST_ID))
                .andExpect(content().string(containsString(selectedGuestOptionFragment())))
                .andExpect(content().string(containsString("Needs late checkout")));

        // Completing the Walk-in creates once, redirects, and clears the state.
        mockMvc.perform(walkInPost("/check-in/walk-in/confirm", "VND").session(session))
                .andExpect(status().is3xxRedirection());
        verify(checkInService, org.mockito.Mockito.times(1)).confirmWalkIn(any());
        mockMvc.perform(getAs("/check-in/walk-in/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/walk-in"));
        mockMvc.perform(getAs("/check-in/walk-in", session))
                .andExpect(content().string(not(containsString("Needs late checkout"))));
    }

    /** Confirms returning to the Check-in Guest landing abandons the in-progress Walk-in, so it cannot leak into a new one. */
    @Test
    void shouldDropTheInProgressWalkInWhenReturningToTheLanding() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND").param("notes", "Stale note").session(session))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(getAs("/check-in", session)).andExpect(status().isOk());

        mockMvc.perform(getAs("/check-in/walk-in", session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Stale note"))));
    }

    /** Builds a representative created-Guest lookup entry matching {@code GUEST_ID}. */
    /** Confirms a Guest with a null date of birth and ID / Passport Number exposes neither value, so the page shows the placeholder. */
    @Test
    void shouldOmitGuestIdentityOptionDataWhenTheGuestHasNone() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guestLookup()));

        String html = mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("data-date-of-birth="));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("data-id-document-number="));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("=\"null\""));
    }

    /** Confirms the Guest created in Create New Guest and returned to the form carries both persisted identity fields. */
    @Test
    void shouldExposeCreatedGuestIdentityFieldsAfterTheCreateNewGuestRoundTrip() throws Exception {
        GuestLookupResponse created = new GuestLookupResponse(
                GUEST_ID, "G000001", "Ann Lee", null, null, "Japan", LocalDate.of(1971, 2, 2), "ID-777123");
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(created));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(created);

        mockMvc.perform(get("/check-in/walk-in")
                        .flashAttr("createdGuestId", GUEST_ID)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"" + GUEST_ID + "\"")))
                .andExpect(content().string(containsString("selected=\"selected\"")))
                .andExpect(content().string(containsString("data-date-of-birth=\"02/02/1971\"")))
                .andExpect(content().string(containsString("data-id-document-number=\"ID-777123\"")));
    }

    /** Confirms the Rate column header carries the shared required marker, in English and in Vietnamese. */
    @Test
    void shouldMarkTheRateHeaderAsRequired() throws Exception {
        String required = "<span aria-hidden=\"true\" class=\"walk-in-required\">*</span>";
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rate (VND / night)</span>")))
                .andExpect(content().string(containsString("Rate (VND / night)</span>\n                                            " + required)));
        mockMvc.perform(get("/check-in/walk-in").param("lang", "vi").with(user("staff").authorities(checkInAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giá (VND / đêm)</span>\n                                            " + required)));
    }

    /** Confirms the browser-side required-field check is given its labels from i18n keys in both languages. */
    @Test
    void shouldRenderRequiredFieldDialogLabelsForTheBrowserCheckInBothLanguages() throws Exception {
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("data-title=\"Required information missing\"")))
                .andExpect(content().string(containsString("data-message=\"Please check the following fields.\"")))
                .andExpect(content().string(containsString("data-adult-count=\"Adults\"")))
                .andExpect(content().string(containsString("data-nightly-rate=\"Rate\"")))
                .andExpect(content().string(containsString("id=\"feedback-dialog\"")))
                .andExpect(content().string(containsString("id=\"feedback-dialog-list\" hidden=\"hidden\"")));
        mockMvc.perform(get("/check-in/walk-in").param("lang", "vi").with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("data-title=\"Thiếu thông tin bắt buộc\"")))
                .andExpect(content().string(containsString("data-adult-count=\"Người lớn\"")))
                .andExpect(content().string(containsString("data-nightly-rate=\"Giá phòng\"")));
    }

    /**
     * Confirms the server still rejects a submitted room without a nightly rate: nothing reaches the service, the
     * entered state is kept, the rate is flagged invalid and named by Room No. in the shared dialog, not the banner.
     */
    @Test
    void shouldRejectMissingNightlyRateOnTheServerAndPreserveTheEnteredState() throws Exception {
        UUID otherRoom = UUID.randomUUID();
        stubRoomNumbers(otherRoom);
        submitAndFollow(post("/check-in/walk-in/review")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                        .param("adultCount", "2")
                        .param("childCount", "0")
                        .param("currency", "VND")
                        .param("notes", "Late arrival")
                        .param("rooms[0].roomId", ROOM_ID.toString())
                        .param("rooms[1].roomId", otherRoom.toString())
                        .param("rooms[1].nightlyRate", "900000")
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<li>Rate — DEMO-303</li>")))
                .andExpect(content().string(not(containsString("<li>Rate — DEMO-404</li>"))))
                .andExpect(content().string(containsString("data-room-id=\"" + ROOM_ID + "\"")))
                .andExpect(content().string(containsString("data-room-id=\"" + otherRoom + "\"")))
                .andExpect(content().string(containsString("data-rate=\"900000\"")))
                .andExpect(content().string(containsString("data-rate-invalid=\"true\"")))
                .andExpect(content().string(containsString("Late arrival")))
                .andExpect(content().string(not(containsString("Please correct the highlighted fields."))));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms exactly one missing Walk-in required field produces a one-item list and no generic banner. */
    @Test
    void shouldListASingleMissingWalkInRequiredField() throws Exception {
        submitAndFollow(walkInPostWith("adultCount", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<li>Adults</li>")))
                .andExpect(content().string(not(containsString("<li>Guest</li>"))))
                .andExpect(content().string(not(containsString("Please correct the highlighted fields."))))
                .andExpect(content().string(containsString("aria-describedby=\"adultCount-error\"")))
                .andExpect(content().string(containsString("class=\"field-error visually-hidden\" id=\"adultCount-error\"")));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms every missing Walk-in required field is listed at once, in order, keeping entries; Check-in/currency are not fields. */
    @Test
    void shouldListEveryMissingWalkInRequiredFieldInEnglish() throws Exception {
        String html = submitAndFollow(post("/check-in/walk-in/review")
                        .param("guestId", "").param("checkOutDate", "").param("adultCount", "").param("childCount", "")
                        .param("currency", "VND").param("notes", "Keep these notes")
                        .with(user("staff").authorities(checkInAuthority())).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("id=\"feedback-dialog-title\">Required information missing</h2>"));
        int previous = -1;
        for (String item : List.of("<li>Guest</li>", "<li>Check-out Date</li>", "<li>Adults</li>", "<li>Children</li>",
                "<li>Room Selection</li>")) {
            int position = html.indexOf(item);
            org.junit.jupiter.api.Assertions.assertTrue(position > previous, item + " missing or out of order");
            previous = position;
        }
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<li>Check-in Date</li>"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<li>OTA"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<li>Currency"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("Please correct the highlighted fields."));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Keep these notes"));
        verify(checkInService, never()).reviewWalkIn(any());
    }

    /** Confirms two selected rooms each missing a rate are listed separately by Room No., in English and Vietnamese. */
    @Test
    void shouldListEachRoomWithAMissingRateByRoomNumberInBothLanguages() throws Exception {
        UUID otherRoom = UUID.randomUUID();
        stubRoomNumbers(otherRoom);
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post = post("/check-in/walk-in/review")
                .param("guestId", GUEST_ID.toString())
                .param("checkOutDate", LocalDate.now().plusDays(2).toString())
                .param("adultCount", "").param("childCount", "0").param("currency", "VND")
                .param("rooms[0].roomId", ROOM_ID.toString()).param("rooms[0].nightlyRate", "")
                .param("rooms[1].roomId", otherRoom.toString()).param("rooms[1].nightlyRate", "")
                .with(user("staff").authorities(checkInAuthority())).with(csrf());
        MockHttpSession session = new MockHttpSession();
        org.springframework.test.web.servlet.MvcResult posted =
                mockMvc.perform(post.session(session)).andExpect(status().is3xxRedirection()).andReturn();

        mockMvc.perform(get("/check-in/walk-in").session(session).flashAttrs(posted.getFlashMap())
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("<li>Adults</li>")))
                .andExpect(content().string(containsString("<li>Rate — DEMO-303</li>")))
                .andExpect(content().string(containsString("<li>Rate — DEMO-404</li>")));
        mockMvc.perform(get("/check-in/walk-in").param("lang", "vi").session(session).flashAttrs(posted.getFlashMap())
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("Vui lòng kiểm tra các trường sau.")))
                .andExpect(content().string(containsString("<li>Người lớn</li>")))
                .andExpect(content().string(containsString("<li>Giá phòng — DEMO-303</li>")))
                .andExpect(content().string(containsString("<li>Giá phòng — DEMO-404</li>")))
                .andExpect(content().string(not(containsString("Vui lòng sửa các trường được đánh dấu."))));
    }

    /** Confirms a domain rejection is not shown as a missing-fields dialog (the capacity test above keeps the banner). */
    @Test
    void shouldNotListAWalkInDomainRejectionAsMissingFields() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenThrow(new com.example.hotel.exception.WalkInReviewException(
                com.example.hotel.exception.WalkInReviewException.Reason.INSUFFICIENT_ADULT_CAPACITY, "capacity", 2, 1));

        submitAndFollow(walkInPost("/check-in/walk-in/review", "VND"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"feedback-dialog-list\" hidden=\"hidden\"")))
                .andExpect(content().string(not(containsString("<li>Adults</li>"))));
    }

    private void stubRoomNumbers(UUID otherRoom) {
        com.example.hotel.dto.room.response.RoomResponse first =
                org.mockito.Mockito.mock(com.example.hotel.dto.room.response.RoomResponse.class);
        when(first.id()).thenReturn(ROOM_ID);
        when(first.roomNumber()).thenReturn("DEMO-303");
        com.example.hotel.dto.room.response.RoomResponse second =
                org.mockito.Mockito.mock(com.example.hotel.dto.room.response.RoomResponse.class);
        when(second.id()).thenReturn(otherRoom);
        when(second.roomNumber()).thenReturn("DEMO-404");
        when(roomQueryService.findAllByIds(any())).thenReturn(List.of(first, second));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder walkInPostWith(
            String field, String value) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("guestId", GUEST_ID.toString());
        values.put("checkOutDate", LocalDate.now().plusDays(2).toString());
        values.put("adultCount", "2");
        values.put("childCount", "0");
        values.put("currency", "VND");
        values.put("rooms[0].roomId", UUID.randomUUID().toString());
        values.put("rooms[0].nightlyRate", "1000000");
        values.put(field, value);
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = post("/check-in/walk-in/review")
                .with(user("staff").authorities(checkInAuthority()))
                .with(csrf());
        values.forEach(request::param);
        return request;
    }

    /** Confirms a valid rate proceeds to the Summary and the rate-error hook is absent from a clean form. */
    @Test
    void shouldProceedToTheSummaryWhenEveryRateIsPresent() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        summaryPage(checkInAuthority())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reservation Summary")))
                .andExpect(content().string(not(containsString("data-rate-error"))));
        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(not(containsString("data-rate-error"))));
    }

    /** Confirms a user allowed to open Room Detail gets the real route for Walk-in rooms and the Summary room number. */
    @Test
    void shouldLinkWalkInAndSummaryRoomNumbersToRoomDetailForRoomManagers() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        mockMvc.perform(get("/check-in/walk-in")
                        .with(user("staff").authorities(checkInAndRoomAuthority())))
                .andExpect(content().string(containsString("data-room-url=\"/rooms/ROOM_ID\"")));
        summaryPage(checkInAndRoomAuthority())
                .andExpect(content().string(containsString("<a class=\"record-link\" href=\"/rooms/" + ROOM_ID + "\">101</a>")));
    }

    /** Confirms the room number stays plain text, with no Room Detail route, without the Room Detail permission. */
    @Test
    void shouldNotLinkRoomNumbersWithoutTheRoomDetailPermission() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        mockMvc.perform(get("/check-in/walk-in").with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(not(containsString("data-room-url="))));
        summaryPage(checkInAuthority())
                .andExpect(content().string(not(containsString("href=\"/rooms/"))))
                .andExpect(content().string(containsString(">101</span>")));
    }

    /**
     * Prepares the Walk-in through the real POST (which keeps the form in the session and redirects) and then opens
     * the Summary as the plain GET page the browser lands on.
     */
    private org.springframework.test.web.servlet.ResultActions summaryPage(List<SimpleGrantedAuthority> authorities)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND")
                        .session(session)
                        .with(user("staff").authorities(authorities)))
                .andExpect(redirectedUrl("/check-in/walk-in/review"));
        return mockMvc.perform(get("/check-in/walk-in/review")
                .session(session)
                .with(user("staff").authorities(authorities)));
    }

    private com.example.hotel.dto.booking.response.WalkInReviewResponse walkInReview() {
        LocalDate today = LocalDate.now();
        return new com.example.hotel.dto.booking.response.WalkInReviewResponse(
                GUEST_ID, "Ann Lee", "G000001", false, null, today, today.plusDays(2), Instant.now(),
                List.of(new com.example.hotel.dto.booking.response.CheckInRoomLine(
                        ROOM_ID, "101", "Double Room", today, today.plusDays(2), new java.math.BigDecimal("1200000"), 2,
                        new java.math.BigDecimal("2400000"), 2, "AVAILABLE")),
                new java.math.BigDecimal("2400000"), "VND");
    }

    /** Confirms the Summary renders the selected Guest's stored contact, nationality with flag, DOB and ID, and the passport document. */
    @Test
    void shouldRenderSummaryGuestIdentityAndPassportDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        when(checkInService.reviewWalkIn(any())).thenReturn(new com.example.hotel.dto.booking.response.WalkInReviewResponse(
                GUEST_ID, "Ann Lee", "G000001", true, documentId, today, today.plusDays(2), Instant.now(),
                walkInReview().rooms(), new java.math.BigDecimal("2400000"), "VND"));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(new GuestLookupResponse(
                GUEST_ID, "G000001", "Ann Lee", "ann@example.com", "0123456", "Japan",
                LocalDate.of(1971, 2, 2), "ID-777123"));

        String passportUrl = "/guests/" + GUEST_ID + "/documents/" + documentId + "/passport";
        summaryPage(checkInAuthority())
                .andExpect(content().string(containsString("Ann Lee")))
                .andExpect(content().string(containsString("🇯🇵")))
                .andExpect(content().string(containsString(">Japan<")))
                .andExpect(content().string(containsString("0123456")))
                .andExpect(content().string(containsString("ann@example.com")))
                .andExpect(content().string(containsString("02/02/1971")))
                .andExpect(content().string(containsString("ID-777123")))
                .andExpect(content().string(containsString("src=\"" + passportUrl + "\"")))
                .andExpect(content().string(containsString("class=\"walk-in-doc\"")));
    }

    /** Confirms a Guest without DOB, ID or passport shows the placeholder and the missing-document text, never a broken image. */
    @Test
    void shouldRenderSummaryPlaceholdersWhenTheGuestHasNoIdentityOrPassport() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guestLookup());

        summaryPage(checkInAuthority())
                .andExpect(content().string(containsString("No passport image on file.")))
                .andExpect(content().string(not(containsString("<img"))))
                .andExpect(content().string(not(containsString("null"))));
    }

    /** Confirms Room Details shows the room's type, capacity, guests, rate, nights, total and the Stay/Notes data from the prepared form. */
    @Test
    void shouldRenderSummaryRoomDetailsStayAndNotes() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND")
                        .param("notes", "Quiet room please")
                        .session(session))
                .andExpect(redirectedUrl("/check-in/walk-in/review"));
        mockMvc.perform(get("/check-in/walk-in/review").session(session)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("Total Room Charge (VND)")))
                .andExpect(content().string(containsString("Adults: 2 · Children: 0")))
                .andExpect(content().string(containsString("1,200,000")))
                .andExpect(content().string(containsString("Quiet room please")))
                .andExpect(content().string(containsString("Actual check-in (preview)")))
                .andExpect(content().string(containsString("Additional Information")))
                .andExpect(content().string(containsString("date-value--check-in")))
                .andExpect(content().string(containsString("Confirm &amp; Check-in")))
                .andExpect(content().string(not(containsString("Create Reservation"))))
                .andExpect(content().string(not(containsString("OTA Booking Reference"))))
                .andExpect(content().string(not(containsString("AGODA"))));
    }

    /** Confirms the primary room image is shown only to users who may open Room Detail, from the authenticated Room endpoint. */
    @Test
    void shouldRenderPrimaryRoomImageOnlyForRoomManagers() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        UUID imageId = UUID.randomUUID();
        when(roomImageService.findByRoomId(ROOM_ID)).thenReturn(List.of(
                new com.example.hotel.dto.room.response.RoomImageResponse(UUID.randomUUID(), "other.jpg", false),
                new com.example.hotel.dto.room.response.RoomImageResponse(imageId, "primary.jpg", true)));

        summaryPage(checkInAndRoomAuthority())
                .andExpect(content().string(containsString(
                        "src=\"/rooms/" + ROOM_ID + "/images/" + imageId + "/file\"")));
        summaryPage(checkInAuthority())
                .andExpect(content().string(not(containsString("/images/"))))
                .andExpect(content().string(containsString("No room image")));
    }

    /** Confirms the Summary renders in Vietnamese with the localized section titles and the unchanged Confirm &amp; Check-in action. */
    @Test
    void shouldRenderSummaryInVietnamese() throws Exception {
        when(checkInService.reviewWalkIn(any())).thenReturn(walkInReview());
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(walkInPost("/check-in/walk-in/review", "VND").session(session))
                .andExpect(redirectedUrl("/check-in/walk-in/review"));

        mockMvc.perform(get("/check-in/walk-in/review").param("lang", "vi").session(session)
                        .with(user("staff").authorities(checkInAuthority())))
                .andExpect(content().string(containsString("Thông tin bổ sung")))
                .andExpect(content().string(containsString("Tổng tiền phòng (VND)")))
                .andExpect(content().string(containsString("Xác nhận &amp; Nhận phòng")))
                .andExpect(content().string(containsString("Phòng 101 (2 đêm × 1,200,000)")));
    }

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
                List.of(),
                Instant.parse("2026-08-25T10:00:00Z"),
                null,
                null,
                null,
                "Nguyen Van A",
                null,
                null,
                true,
                null,
                0L);
    }

    /** Builds a representative Check-in Review response with an available passport image. */
    private CheckInReviewResponse reviewResponseWithPassport(CheckInTiming timing, boolean eligible, UUID documentId) {
        CheckInReviewResponse base = reviewResponse(timing, eligible, null);
        return new CheckInReviewResponse(
                base.reservationId(), base.reservationNumber(), base.status(), base.eligibleForCheckIn(),
                base.timing(), base.scheduledCheckInDate(), base.scheduledCheckOutDate(), base.currentHotelDate(),
                base.actualCheckInPreview(), base.source(), base.otaBookingReference(), base.guestId(),
                base.guestFullName(), base.guestCode(), base.guestNationality(), true, base.rooms(),
                base.totalAmount(), base.currency(), base.readiness(), base.adultCount(), base.childCount(),
                base.accompanyingGuests(), base.reservedAt(), base.guestDateOfBirth(), base.guestPhone(),
                base.guestEmail(), base.effectiveBookingContactName(), base.effectiveBookingContactPhone(),
                base.effectiveBookingContactEmail(), base.bookingContactFromPrimaryGuest(), documentId,
                base.arrivalOverdueDays());
    }

    /** Builds the CHECK_IN authority. */
    private static List<SimpleGrantedAuthority> checkInAndRoomAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"), new SimpleGrantedAuthority("PERM_MANAGE_ROOM"));
    }

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
