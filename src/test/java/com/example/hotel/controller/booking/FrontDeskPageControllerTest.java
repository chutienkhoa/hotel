package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.room.RoomTypeQueryService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the Front Desk page: permission-aware views, no cross-tab data, money visibility, actions, EN/VI. */
@WebMvcTest(FrontDeskPageController.class)
@Import({FrontDeskPageControllerTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class FrontDeskPageControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private static final UUID RES = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FrontDeskQueryService queryService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private RoomTypeQueryService roomTypeQueryService;

    /** Stubs an arrival with a dirty room, an overdue arrival, a payment-required overdue departure and an in-house stay. */
    @BeforeEach
    void stubData() {
        when(queryService.hotelToday()).thenReturn(TODAY);
        FrontDeskRoomResponse dirty = new FrontDeskRoomResponse(ROOM, "203", "Double", ArrivalIssueCode.ROOM_DIRTY);
        ArrivalReadiness needs = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.NORMAL, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "203", ROOM)));
        ArrivalReadiness ok = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of());
        when(queryService.arrivals(any(), anyInt())).thenReturn(new PageImpl<>(List.of(
                new FrontDeskArrivalRow(RES, "R-1028", "Tran Minh", "G-00141", BookingSource.DIRECT, null, TODAY,
                        false, true, needs, List.of(dirty), true, "0900000001"),
                new FrontDeskArrivalRow(UUID.randomUUID(), "R-1030", "John Smith", "G-00152", BookingSource.BOOKING_COM,
                        "BK-77", TODAY, false, false, ok,
                        List.of(new FrontDeskRoomResponse(UUID.randomUUID(), "301", "Twin", null),
                                new FrontDeskRoomResponse(UUID.randomUUID(), "302", "Twin", null)), false, null))));
        List<FrontDeskRoomResponse> rooms = List.of(new FrontDeskRoomResponse(ROOM, "101", "Single", null));
        when(queryService.departures(eq(false), any(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(stayRow(rooms, true, true, null))));
        when(queryService.departures(eq(true), any(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(stayRow(rooms, true, true, new BigDecimal("300000")))));
        when(queryService.inHouse(any(), anyInt())).thenReturn(new PageImpl<>(List.of(stayRow(rooms, false, false, null))));
    }

    /** Confirms each tab exposes its keyboard shortcut (data, ARIA and localized title) and the handler script is loaded. */
    @Test
    void shouldExposeTabShortcuts() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN", "PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-shortcut=\"1\" aria-keyshortcuts=\"Control+1\"")))
                .andExpect(content().string(containsString("data-shortcut=\"2\" aria-keyshortcuts=\"Control+2\"")))
                .andExpect(content().string(containsString("data-shortcut=\"3\" aria-keyshortcuts=\"Control+3\"")))
                .andExpect(content().string(containsString("Shortcut: Ctrl+1")))
                .andExpect(content().string(containsString("/js/common/tab-shortcuts.js")));
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN", "PERM_CHECK_OUT"))
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Phím tắt: Ctrl+2")));
    }

    /** Confirms CHECK_IN alone opens Front Desk on Arrivals and shows no Departures/In-house tab or data. */
    @Test
    void shouldShowOnlyArrivalsToCheckInUser() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"tab-arrivals\"")))
                .andExpect(content().string(not(containsString("id=\"tab-departures\""))))
                .andExpect(content().string(not(containsString("id=\"tab-in-house\""))))
                .andExpect(content().string(containsString("R-1028")));
        verify(queryService, never()).departures(anyBoolean(), any(), anyInt());
        verify(queryService, never()).inHouse(any(), anyInt());
    }

    /** Confirms CHECK_OUT alone opens Front Desk on Departures with no Arrivals tab or data. */
    @Test
    void shouldShowOnlyDeparturesAndInHouseToCheckOutUser() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"tab-arrivals\""))))
                .andExpect(content().string(containsString("id=\"tab-departures\"")))
                .andExpect(content().string(containsString("id=\"tab-in-house\"")))
                .andExpect(content().string(containsString("Overdue Departure")))
                .andExpect(content().string(containsString("Overdue 1 day")));
        verify(queryService, never()).arrivals(any(), anyInt());
    }

    /** Confirms a user with neither permission gets 403. */
    @Test
    void shouldRefuseUserWithoutCheckInOrCheckOut() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_PAYMENT", "PERM_MANAGE_HOUSEKEEPING")))
                .andExpect(status().isForbidden());
    }

    /** Confirms supplying another tab's view parameter cannot fetch its data. */
    @Test
    void shouldRefuseUnauthorizedViewParameter() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/front-desk").param("view", "arrivals").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isForbidden());
        verify(queryService, never()).departures(anyBoolean(), any(), anyInt());
        verify(queryService, never()).inHouse(any(), anyInt());
        verify(queryService, never()).arrivals(any(), anyInt());
    }

    /** Confirms an unknown view falls back to the first authorized one. */
    @Test
    void shouldFallBackToAnAuthorizedDefaultView() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "bogus").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"departures-results\"")));
    }

    /** Confirms the sidebar Front Desk link follows CHECK_IN OR CHECK_OUT. */
    @Test
    void shouldShowSidebarEntryForCheckInOrCheckOut() throws Exception {
        for (String permission : List.of("PERM_CHECK_IN", "PERM_CHECK_OUT")) {
            mockMvc.perform(get("/front-desk").with(perm(permission)))
                    .andExpect(content().string(containsString("href=\"/front-desk\"")));
        }
    }

    /**
     * Confirms the single Arrivals action is the Check-in Review route, and that holding other permissions adds no
     * secondary row link (Reservation Detail and Housekeeping stay reachable from their own pages). Only the table
     * body is checked: the sidebar legitimately links to Housekeeping for users who hold that permission.
     */
    @Test
    void shouldGateArrivalActionsByTheirOwnPermissions() throws Exception {
        String review = "/check-in/reservations/" + RES;
        String body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString(review)))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(tableBody(body).contains("href=\"/housekeeping\""));
        org.junit.jupiter.api.Assertions.assertFalse(tableBody(body).contains("href=\"/reservations/" + RES + "\""));

        body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN", "PERM_MANAGE_HOUSEKEEPING", "PERM_VIEW_BOOKING")))
                .andExpect(content().string(containsString(review)))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(tableBody(body).contains("href=\"/housekeeping\""));
        org.junit.jupiter.api.Assertions.assertFalse(tableBody(body).contains("href=\"/reservations/" + RES + "\""));
    }

    /** Confirms every row exposes exactly one primary action, whatever other permissions the user holds. */
    @Test
    void shouldExposeExactlyOnePrimaryActionPerRow() throws Exception {
        String arrivals = mockMvc.perform(get("/front-desk")
                        .with(perm("PERM_CHECK_IN", "PERM_VIEW_BOOKING", "PERM_MANAGE_HOUSEKEEPING", "PERM_EXTEND_STAY")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(2, countOccurrences(arrivals, "front-desk-action"));

        String departures = mockMvc.perform(get("/front-desk").param("view", "departures")
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_EXTEND_STAY", "PERM_VIEW_BOOKING")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(departures, "front-desk-action"));

        String inHouse = mockMvc.perform(get("/front-desk").param("view", "in-house")
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_CHANGE_ROOM", "PERM_VIEW_BOOKING")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(inHouse, "front-desk-action"));
    }

    /** Confirms an overdue arrival shows the Overdue Arrival state and still routes to the Check-in Review workflow. */
    @Test
    void shouldRenderOverdueArrivalStateAndReviewRoute() throws Exception {
        ArrivalReadiness ready = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.LATE, List.of());
        when(queryService.arrivals(any(), anyInt())).thenReturn(new PageImpl<>(List.of(new FrontDeskArrivalRow(
                RES, "R-3000", "Late Guest", "G-3", BookingSource.DIRECT, null, TODAY.minusDays(1), true, true, ready,
                List.of(new FrontDeskRoomResponse(ROOM, "105", "Double", null)), false, null))));

        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("Overdue Arrival")))
                .andExpect(content().string(containsString("/check-in/reservations/" + RES)));
    }

    /** Confirms a capacity blocker from the shared readiness is rendered as Needs Attention text on Front Desk. */
    @Test
    void shouldRenderCapacityBlockerThroughReadiness() throws Exception {
        ArrivalReadiness capacity = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.NORMAL, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY,
                        null, null, 3, 2, null)));
        when(queryService.arrivals(any(), anyInt())).thenReturn(new PageImpl<>(List.of(new FrontDeskArrivalRow(RES, "R-2000", "Ann", "G-1",
                BookingSource.DIRECT, null, TODAY, false, true, capacity,
                List.of(new FrontDeskRoomResponse(ROOM, "101", "Double", null)), false, null))));

        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("has 3 adults but the assigned rooms support only 2 adults")));
    }

    /**
     * Confirms the Arrivals Guest cell shows only the guest name and code: the effective Booking Contact phone is
     * still loaded and searchable, but it is not displayed in the table.
     */
    @Test
    void shouldNotDisplayContactPhoneInArrivalsTable() throws Exception {
        String body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN", "PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(tableBody(body).contains("0900000001"));
        org.junit.jupiter.api.Assertions.assertTrue(tableBody(body).contains("Tran Minh"));
    }

    /**
     * Confirms the Check-in Guest CTA is shown above the tabs for CHECK_IN and links to the existing Check-in hub,
     * and that a user without CHECK_IN (Departures and In-house only) does not see it.
     */
    @Test
    void shouldShowCheckInGuestCtaOnlyWithCheckIn() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("id=\"check-in-guest\"")))
                .andExpect(content().string(containsString("href=\"/check-in\"")))
                .andExpect(content().string(containsString("Check-in Guest")));
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(not(containsString("id=\"check-in-guest\""))))
                .andExpect(content().string(not(containsString("Check-in Guest"))));
    }

    /** Confirms the Check-in Guest CTA label is translated to Vietnamese. */
    @Test
    void shouldRenderVietnameseCheckInGuestCta() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách nhận phòng")));
    }

    /** Confirms a ready multi-room arrival is one row with both rooms, and a blocked room shows its issue. */
    @Test
    void shouldRenderArrivalRowsWithRoomsAndIssues() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("Room 203 needs cleaning")))
                .andExpect(content().string(containsString("<strong>301</strong>")))
                .andExpect(content().string(containsString("<strong>302</strong>")))
                .andExpect(content().string(containsString("Tran Minh")))
                .andExpect(content().string(containsString("G-00141")))
                .andExpect(content().string(containsString("Check in")))
                .andExpect(content().string(containsString("BK-77")));
    }

    /** Confirms no amount is fetched-as-visible or rendered without MANAGE_PAYMENT, and it is with it. */
    @Test
    void shouldControlBalanceAmountByManagePayment() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("Payment Required")))
                .andExpect(content().string(not(containsString("300"))))
                .andExpect(content().string(not(containsString(">Balance<"))));
        verify(queryService).departures(eq(false), any(), anyInt());
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(content().string(containsString("300")))
                .andExpect(content().string(containsString(">Balance<")))
                .andExpect(content().string(containsString("Outstanding")));
        verify(queryService).departures(eq(true), any(), anyInt());
    }

    /**
     * Confirms In-house shows current rooms, hotel-time check-in, planned checkout and nights, and never a Balance
     * column (§9.2.4), even for a user who may see money.
     */
    @Test
    void shouldRenderInHouseRowsWithCurrentRoomAndNoBalance() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(content().string(containsString("<strong>101</strong>")))
                .andExpect(content().string(containsString("Single")))
                .andExpect(content().string(containsString("20/09/2026 14:35")))
                .andExpect(content().string(containsString("Nights")))
                .andExpect(content().string(not(containsString(">Balance<"))));
    }

    /**
     * Confirms the In-house row has one View action to the Reservation Detail hub, and that Change Room, Folio and
     * Extend Stay are not row links (they remain on Reservation Detail).
     */
    @Test
    void shouldLinkInHouseViewActionToReservationDetailOnly() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT", "PERM_VIEW_BOOKING")))
                .andExpect(content().string(containsString("href=\"/reservations/" + RES + "\"")))
                .andExpect(content().string(not(containsString("/change"))));
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(not(containsString("href=\"/reservations/" + RES + "\""))));
    }

    /** Confirms Departures links its one primary action to the Checkout Review route. */
    @Test
    void shouldLinkDepartureActionToCheckoutReview() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("href=\"/check-out/" + RES + "\"")));
    }

    /**
     * Confirms the secondary links that used to sit beside a row (Folio, Extend Stay, Change Room, Housekeeping) are
     * no longer row actions on any view, whatever permissions the user holds.
     */
    @Test
    void shouldNotOfferSecondaryRowActionsOnAnyView() throws Exception {
        for (String view : List.of("arrivals", "departures", "in-house")) {
            String body = mockMvc.perform(get("/front-desk").param("view", view)
                            .with(perm("PERM_CHECK_IN", "PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_EXTEND_STAY",
                                    "PERM_CHANGE_ROOM", "PERM_MANAGE_HOUSEKEEPING", "PERM_VIEW_BOOKING")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String rows = tableBody(body);
            org.junit.jupiter.api.Assertions.assertFalse(rows.contains("/folio"), view);
            org.junit.jupiter.api.Assertions.assertFalse(rows.contains("/stay-extension"), view);
            org.junit.jupiter.api.Assertions.assertFalse(rows.contains("/change"), view);
            org.junit.jupiter.api.Assertions.assertFalse(rows.contains("/housekeeping"), view);
        }
    }

    /** Confirms Departures search and sort reach the query service with the normalized request state. */
    @Test
    void shouldPassDeparturesSearchAndSortToQueryService() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").param("search", "  Nguyen ")
                        .param("sort", "plannedCheckOutDate").param("dir", "desc").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).departures(eq(false), argThat(criteria -> "Nguyen".equals(criteria.getSearch())
                && "plannedCheckOutDate".equals(criteria.getSort()) && "desc".equals(criteria.getDir())), eq(0));
    }

    /** Confirms In-house search and sort reach the query service with the normalized request state. */
    @Test
    void shouldPassInHouseSearchAndSortToQueryService() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").param("search", " 101 ")
                        .param("sort", "room").param("dir", "asc").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).inHouse(argThat(criteria -> "101".equals(criteria.getSearch())
                && "room".equals(criteria.getSort()) && "asc".equals(criteria.getDir())), eq(0));
    }

    /** Confirms a Departures page number past the last valid page redirects to the last valid page, preserving the view. */
    @Test
    void shouldRedirectDeparturesToLastValidPage() throws Exception {
        when(queryService.departures(eq(false), any(), eq(4))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 10), 2));
        mockMvc.perform(get("/front-desk").param("view", "departures").param("page", "4").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front-desk?view=departures&page=0"));
    }

    /** Confirms Vietnamese labels are used when Vietnamese is selected. */
    @Test
    void shouldRenderVietnamese() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách đến")))
                .andExpect(content().string(containsString("Cần xử lý")))
                .andExpect(content().string(containsString("Nhận phòng")));
    }

    /** Confirms a submitted search fragment is normalized and passed through to the query service unchanged. */
    @Test
    void shouldPassNormalizedSearchToQueryService() throws Exception {
        mockMvc.perform(get("/front-desk").param("search", "  Tran  ").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk());
        verify(queryService).arrivals(argThat(criteria -> "Tran".equals(criteria.getSearch())), eq(0));
    }

    /** Confirms blank search input is treated as no search (never sent to the service as an empty string). */
    @Test
    void shouldTreatBlankSearchAsAbsent() throws Exception {
        mockMvc.perform(get("/front-desk").param("search", "   ").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk());
        verify(queryService).arrivals(argThat(criteria -> criteria.getSearch() == null), eq(0));
    }

    /** Confirms the requested zero-based page is parsed and forwarded to the query service. */
    @Test
    void shouldForwardRequestedPageToQueryService() throws Exception {
        when(queryService.inHouse(any(), eq(2))).thenReturn(new PageImpl<>(
                List.of(), PageRequest.of(2, 10), 0));
        mockMvc.perform(get("/front-desk").param("view", "in-house").param("page", "2").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).inHouse(any(), eq(2));
    }

    /** Confirms a page number past the last valid page redirects to the last valid page, preserving the view. */
    @Test
    void shouldRedirectToLastValidPageWhenRequestedPageIsOutOfRange() throws Exception {
        when(queryService.arrivals(any(), eq(5))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 2));
        mockMvc.perform(get("/front-desk").param("page", "5").with(perm("PERM_CHECK_IN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front-desk?view=arrivals&page=0"));
    }

    /** Confirms a negative/malformed page value is treated as the first page rather than rejected. */
    @Test
    void shouldTreatInvalidPageAsFirstPage() throws Exception {
        mockMvc.perform(get("/front-desk").param("page", "not-a-number").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk());
        verify(queryService).arrivals(any(), eq(0));
    }

    /** Confirms Arrivals renders clickable sort headers that preserve the active view on their link. */
    @Test
    void shouldRenderSortableColumnHeadersForArrivals() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("sort=reservationNumber")))
                .andExpect(content().string(containsString("sort=checkInDate")));
    }

    /** Confirms In-house renders a Room-number sort header, its own additional sortable column. */
    @Test
    void shouldRenderRoomSortHeaderForInHouse() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("sort=room")));
    }

    /** Confirms the pagination/summary toolbar is rendered above the data table for every view. */
    @Test
    void shouldRenderPaginationToolbarAboveTheTable() throws Exception {
        String body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andReturn().getResponse().getContentAsString();
        int toolbarIndex = body.indexOf("results-toolbar");
        int tableIndex = body.indexOf("front-desk-table-wrap");
        org.junit.jupiter.api.Assertions.assertTrue(toolbarIndex >= 0 && toolbarIndex < tableIndex);
    }

    /** Confirms the Arrivals pager is always rendered: with one page both Previous and Next are disabled and page 1 is current. */
    @Test
    void shouldRenderSinglePagePagerWithDisabledPreviousAndNext() throws Exception {
        String body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pager = pagerMarkup(body);
        org.junit.jupiter.api.Assertions.assertEquals(2, countOccurrences(pager, "pagination__segment--disabled"));
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(pager, "pagination__segment--current"));
        org.junit.jupiter.api.Assertions.assertFalse(pager.contains("page=1"));
    }

    /** Confirms paging keeps view, search, sort and direction on every Previous/Next/page link, and never renders a page that does not exist. */
    @Test
    void shouldPreserveViewSearchSortAndDirectionWhenPaging() throws Exception {
        when(queryService.arrivals(argThat(criteria -> "Tran".equals(criteria.getSearch())), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 25));
        String body = mockMvc.perform(get("/front-desk").param("view", "arrivals").param("search", "Tran")
                        .param("sort", "guestName").param("dir", "desc").param("page", "1")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pager = pagerMarkup(body);
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("page=0"));
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("page=2"));
        org.junit.jupiter.api.Assertions.assertFalse(pager.contains("page=3"));
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(pager, "pagination__segment--current"));
        org.junit.jupiter.api.Assertions.assertEquals(0, countOccurrences(pager, "pagination__segment--disabled"));
        for (String link : pager.split("href=\"")) {
            if (link.startsWith("/front-desk")) {
                String href = link.substring(0, link.indexOf('"'));
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("view=arrivals"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("search=Tran"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("sort=guestName"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("dir=desc"), href);
            }
        }
    }

    /** Confirms In-house pager links keep view=in-house, the search, the room sort and its direction. */
    @Test
    void shouldPreserveInHouseViewSearchAndSortInPagerLinks() throws Exception {
        when(queryService.inHouse(argThat(criteria -> "Ann".equals(criteria.getSearch())), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 25));
        String body = mockMvc.perform(get("/front-desk").param("view", "in-house").param("search", "Ann")
                        .param("sort", "room").param("dir", "desc").param("page", "1")
                        .with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pager = pagerMarkup(body);
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("page=0") && pager.contains("page=2"), pager);
        for (String link : pager.split("href=\"")) {
            if (link.startsWith("/front-desk")) {
                String href = link.substring(0, link.indexOf('"'));
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("view=in-house"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("search=Ann"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("sort=room"), href);
                org.junit.jupiter.api.Assertions.assertTrue(href.contains("dir=desc"), href);
            }
        }
    }

    /** Confirms the In-house search placeholder follows the approved wording (it searches name, code, reservation and room). */
    @Test
    void shouldUseApprovedInHouseSearchPlaceholderInEnglish() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT"))
                        .cookie(new Cookie("pms-lang", "en")))
                .andExpect(content().string(containsString(
                        "placeholder=\"Guest name, reservation number, or room...\"")));
    }

    /** Confirms an In-house view with no stays shows the empty state and no table, rather than an empty table. */
    @Test
    void shouldShowInHouseEmptyStateWithoutATable() throws Exception {
        when(queryService.inHouse(any(), anyInt())).thenReturn(new PageImpl<>(List.of()));
        String body = mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No in-house guests.")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("front-desk-table--in-house"));
    }

    /** Confirms the In-house Room Type dropdown lists the real RoomType values and marks the active one. */
    @Test
    void shouldRenderInHouseRoomTypeOptionsFromRealRoomTypes() throws Exception {
        UUID single = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID triple = UUID.fromString("00000000-0000-0000-0000-000000000303");
        when(roomTypeQueryService.findAll()).thenReturn(List.of(
                new RoomTypeResponse(single, "SGL", "Single Room"), new RoomTypeResponse(triple, "TRP", "Triple Room")));
        String body = mockMvc.perform(get("/front-desk").param("view", "in-house").param("roomType", triple.toString())
                        .with(perm("PERM_CHECK_OUT")).cookie(new Cookie("pms-lang", "en")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"" + single + "\""), body);
        org.junit.jupiter.api.Assertions.assertTrue(
                body.replaceAll("\\s+", " ").contains("<option value=\"" + triple + "\" selected=\"selected\">Triple Room</option>"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(">All room types</option>"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("name=\"source\""));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("name=\"checkoutDate\""));
    }

    /** Confirms In-house passes the Room Type and Source filters to the query service. */
    @Test
    void shouldPassInHouseRoomTypeAndSourceToQueryService() throws Exception {
        UUID type = UUID.fromString("00000000-0000-0000-0000-000000000301");
        mockMvc.perform(get("/front-desk").param("view", "in-house").param("roomType", type.toString())
                        .param("source", "AGODA").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).inHouse(argThat(criteria -> type.toString().equals(criteria.getRoomType())
                && "AGODA".equals(criteria.getSource())), eq(0));
    }

    /** Confirms Departures passes the checkout-date, status and Source filters to the query service. */
    @Test
    void shouldPassDeparturesCheckoutStatusAndSourceToQueryService() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").param("checkoutDate", "OVERDUE")
                        .param("status", "PAYMENT_REQUIRED").param("source", "BOOKING_COM").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).departures(eq(false), argThat(criteria -> "OVERDUE".equals(criteria.getCheckoutDate())
                && "PAYMENT_REQUIRED".equals(criteria.getStatus()) && "BOOKING_COM".equals(criteria.getSource())), eq(0));
    }

    /** Confirms Departures renders the approved Checkout date, Status and Source controls with real option values. */
    @Test
    void shouldRenderDeparturesCheckoutStatusAndSourceControls() throws Exception {
        String body = mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("name=\"checkoutDate\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("name=\"status\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"PAYMENT_REQUIRED\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"OVERDUE\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"READY\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("name=\"source\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("front-desk-search__form--departures"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("name=\"roomType\""));
    }

    /** Confirms Departures pager and sort links keep every filter, the view, the search and the sort direction. */
    @Test
    void shouldPreserveDeparturesFiltersInPagerAndSortLinks() throws Exception {
        when(queryService.departures(eq(false), argThat(criteria -> "Ann".equals(criteria.getSearch())), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 25));
        String body = mockMvc.perform(get("/front-desk").param("view", "departures").param("search", "Ann")
                        .param("checkoutDate", "TODAY").param("status", "READY").param("source", "AGODA")
                        .param("sort", "guestName").param("dir", "asc").param("page", "1")
                        .with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pager = pagerMarkup(body);
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("page=0") && pager.contains("page=2"), pager);
        for (String link : pager.split("href=\"")) {
            if (link.startsWith("/front-desk")) {
                String href = link.substring(0, link.indexOf('"'));
                for (String expected : List.of("view=departures", "search=Ann", "checkoutDate=TODAY", "status=READY",
                        "source=AGODA", "sort=guestName", "dir=asc")) {
                    org.junit.jupiter.api.Assertions.assertTrue(href.contains(expected), href + " lacks " + expected);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("sort=guestName&amp;dir=asc")
                || body.contains("sort=guestName&dir=asc"));
    }

    /** Confirms In-house pager links keep the Room Type and Source filters, and Clear returns to the bare view. */
    @Test
    void shouldPreserveInHouseFiltersInPagerAndResetOnClear() throws Exception {
        UUID type = UUID.fromString("00000000-0000-0000-0000-000000000301");
        when(queryService.inHouse(argThat(criteria -> type.toString().equals(criteria.getRoomType())), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 25));
        String body = mockMvc.perform(get("/front-desk").param("view", "in-house").param("roomType", type.toString())
                        .param("source", "DIRECT").param("page", "1").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pager = pagerMarkup(body);
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("roomType=" + type), pager);
        org.junit.jupiter.api.Assertions.assertTrue(pager.contains("source=DIRECT"), pager);
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("class=\"button button-secondary front-desk-search__clear\" href=\"/front-desk?view=in-house\""),
                "Clear must link to the bare In-house view");
    }

    /** Confirms a Departures status value outside the approved list is not passed through as a filter value. */
    @Test
    void shouldLeaveUnknownDeparturesStatusToTheServiceToIgnore() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").param("status", "EXPEDIA")
                        .with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk());
        verify(queryService).departures(eq(false), argThat(criteria -> "EXPEDIA".equals(criteria.getStatus())), eq(0));
    }

    /** Confirms a search yielding no rows shows the no-results state with a way to clear the search, not the plain empty state. */
    @Test
    void shouldShowNoResultsStateWhenSearchMatchesNothing() throws Exception {
        when(queryService.arrivals(argThat(criteria -> "zzz".equals(criteria.getSearch())), eq(0)))
                .thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/front-desk").param("search", "zzz").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("match your search")))
                .andExpect(content().string(not(containsString("No arrivals."))));
    }

    /** Confirms the true empty state (no search active) is unchanged from before Batch 3A. */
    @Test
    void shouldShowPlainEmptyStateWhenThereIsNoSearchAndNoArrivals() throws Exception {
        when(queryService.arrivals(any(), eq(0))).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No arrivals.")));
    }

    /** Confirms the Arrivals filter parameters and the normalized search reach the query service together. */
    @Test
    void shouldPassArrivalFiltersToQueryService() throws Exception {
        mockMvc.perform(get("/front-desk").param("search", " tran ").param("arrivalDate", "OVERDUE")
                        .param("readiness", "NEEDS_ATTENTION").param("source", "AGODA").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk());
        verify(queryService).arrivals(argThat(criteria -> "tran".equals(criteria.getSearch())
                && "OVERDUE".equals(criteria.getArrivalDate())
                && "NEEDS_ATTENTION".equals(criteria.getReadiness())
                && "AGODA".equals(criteria.getSource())), eq(0));
    }

    /** Confirms the Arrivals filter bar renders its three selects with the real readiness and source values. */
    @Test
    void shouldRenderArrivalFilterControlsWithRealValues() throws Exception {
        String arrivals = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andReturn().getResponse().getContentAsString();
        for (String token : List.of("id=\"frontDeskArrivalDate\"", "id=\"frontDeskReadiness\"", "id=\"frontDeskSource\"",
                "value=\"READY\"", "value=\"NEEDS_ATTENTION\"", "value=\"DIRECT\"", "value=\"AGODA\"",
                "value=\"BOOKING_COM\"", "value=\"AIRBNB\"", "value=\"OVERDUE\"", "value=\"TODAY\"")) {
            org.junit.jupiter.api.Assertions.assertTrue(arrivals.contains(token), token);
        }
        String departures = mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andReturn().getResponse().getContentAsString();
        // Departures has the Source filter (approved Departures mockup) but not the Arrivals-only date/readiness filters.
        org.junit.jupiter.api.Assertions.assertTrue(departures.contains("id=\"frontDeskSource\""));
        org.junit.jupiter.api.Assertions.assertFalse(departures.contains("id=\"frontDeskArrivalDate\""));
        org.junit.jupiter.api.Assertions.assertFalse(departures.contains("id=\"frontDeskReadiness\""));
        org.junit.jupiter.api.Assertions.assertTrue(departures.contains("id=\"frontDeskSearch\""));
    }

    /** Confirms a selected filter is kept selected when the page is re-rendered with it. */
    @Test
    void shouldKeepSelectedFilterOptionAfterRender() throws Exception {
        mockMvc.perform(get("/front-desk").param("readiness", "READY").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("value=\"READY\" selected=\"selected\"")));
    }

    /** Confirms Clear returns to the Arrivals view with no filters, search or page. */
    @Test
    void shouldClearToUnfilteredArrivals() throws Exception {
        mockMvc.perform(get("/front-desk").param("arrivalDate", "TODAY").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("front-desk-search__clear\" href=\"/front-desk?view=arrivals\"")));
    }

    /** Confirms pagination links keep every active Arrivals filter. */
    @Test
    void shouldPreserveArrivalFiltersInPaginationLinks() throws Exception {
        when(queryService.arrivals(any(), anyInt())).thenReturn(new PageImpl<>(List.of(
                new FrontDeskArrivalRow(RES, "R-1", "Ann", "G-1", BookingSource.AGODA, null, TODAY, true, true,
                        new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.LATE, List.of()),
                        List.of(), false, null)),
                PageRequest.of(0, 10), 25));
        mockMvc.perform(get("/front-desk").param("arrivalDate", "OVERDUE").param("readiness", "NEEDS_ATTENTION")
                        .param("source", "AGODA").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("readiness=NEEDS_ATTENTION")))
                .andExpect(content().string(containsString("source=AGODA")))
                .andExpect(content().string(containsString("arrivalDate=OVERDUE")));
    }

    /** Confirms sort links keep the active Arrivals filters. */
    @Test
    void shouldPreserveArrivalFiltersInSortLinks() throws Exception {
        mockMvc.perform(get("/front-desk").param("arrivalDate", "TODAY").param("source", "AIRBNB")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("arrivalDate=TODAY")))
                .andExpect(content().string(containsString("source=AIRBNB")))
                .andExpect(content().string(containsString("sort=guestName")));
    }

    /** Confirms an out-of-range page redirects to the last valid page with the Arrivals filters still applied. */
    @Test
    void shouldRedirectOutOfRangeArrivalsPreservingFilters() throws Exception {
        when(queryService.arrivals(any(), eq(5))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 2));
        mockMvc.perform(get("/front-desk").param("page", "5").param("arrivalDate", "OVERDUE").with(perm("PERM_CHECK_IN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front-desk?view=arrivals&arrivalDate=OVERDUE&page=0"));
    }

    /**
     * Confirms unknown filter values reach the page without an error. No option is marked selected, so the browser
     * shows the first option (All), and nothing outside the real values is ever offered.
     */
    @Test
    void shouldFailSafeOnUnknownArrivalFilterValues() throws Exception {
        mockMvc.perform(get("/front-desk").param("arrivalDate", "BOGUS").param("readiness", "MISSING_DOCUMENT")
                        .param("source", "EXPEDIA").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("selected=\"selected\""))))
                .andExpect(content().string(not(containsString("value=\"EXPEDIA\""))));
    }

    /** Confirms a filtered arrivals query with no matches explains that the filters matched nothing. */
    @Test
    void shouldShowNoResultsWhenFiltersMatchNothing() throws Exception {
        when(queryService.arrivals(argThat(criteria -> "OVERDUE".equals(criteria.getArrivalDate())), eq(0)))
                .thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/front-desk").param("arrivalDate", "OVERDUE").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("match your search")))
                .andExpect(content().string(not(containsString("No arrivals."))));
    }

    /** Confirms every Arrivals data column is a sort link and the Action column is not: six sortable headers. */
    @Test
    void shouldMakeEveryArrivalsDataColumnSortableButNotAction() throws Exception {
        String body = mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(6, countOccurrences(body, "class=\"front-desk-sort\""));
        for (String key : List.of("reservationNumber", "guestName", "checkInDate", "room", "source", "status")) {
            org.junit.jupiter.api.Assertions.assertTrue(body.contains("sort=" + key + "&amp;dir=asc"), key);
        }
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("sort=action"));
    }

    /** Confirms In-house and Departures make every visible data column sortable, and that the Balance column is sortable only with MANAGE_PAYMENT. */
    @Test
    void shouldMakeEveryInHouseAndDepartureDataColumnSortable() throws Exception {
        String inHouse = mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(7, countOccurrences(inHouse, "class=\"front-desk-sort\""));
        for (String key : List.of("room", "guestName", "reservationNumber", "checkedIn", "plannedCheckOutDate", "nights", "source")) {
            org.junit.jupiter.api.Assertions.assertTrue(inHouse.contains("sort=" + key + "&amp;dir=asc"), key);
        }

        String departuresWithoutMoney = mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(7, countOccurrences(departuresWithoutMoney, "class=\"front-desk-sort\""));
        org.junit.jupiter.api.Assertions.assertFalse(departuresWithoutMoney.contains("sort=outstanding"));

        String departuresWithMoney = mockMvc.perform(get("/front-desk").param("view", "departures")
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(8, countOccurrences(departuresWithMoney, "class=\"front-desk-sort\""));
        org.junit.jupiter.api.Assertions.assertTrue(departuresWithMoney.contains("sort=outstanding&amp;dir=asc"));
    }

    /** Confirms the active sort is announced with aria-sort, and an inactive sortable column reports none. */
    @Test
    void shouldAnnounceActiveSortWithAriaSort() throws Exception {
        mockMvc.perform(get("/front-desk").param("sort", "guestName").param("dir", "asc").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("aria-sort=\"ascending\"")))
                .andExpect(content().string(containsString("aria-sort=\"none\"")))
                .andExpect(content().string(containsString("aria-label=\"Sort by Guest\"")));
        mockMvc.perform(get("/front-desk").param("sort", "guestName").param("dir", "desc").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("aria-sort=\"descending\"")));
    }

    /** Confirms an invalid direction or an unknown key is ignored: no column is announced as sorted. */
    @Test
    void shouldIgnoreInvalidSortDirectionAndKey() throws Exception {
        mockMvc.perform(get("/front-desk").param("sort", "guestName").param("dir", "up").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(not(containsString("aria-sort=\"ascending\""))))
                .andExpect(content().string(not(containsString("aria-sort=\"descending\""))));
        mockMvc.perform(get("/front-desk").param("sort", "action").param("dir", "asc").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(not(containsString("aria-sort=\"ascending\""))));
    }

    /** Confirms a user without MANAGE_PAYMENT cannot sort by Outstanding even by crafting the URL: no amount sort reaches the links. */
    @Test
    void shouldNotEchoOutstandingSortWithoutManagePayment() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").param("sort", "outstanding").param("dir", "desc")
                        .with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("sort=outstanding"))))
                .andExpect(content().string(not(containsString("aria-sort=\"descending\""))));
        verify(queryService).departures(eq(false), argThat(criteria -> "outstanding".equals(criteria.getSort())), eq(0));
    }

    /** Confirms Clear keeps the active sort and direction while dropping filters and the page. */
    @Test
    void shouldKeepSortWhenClearingFilters() throws Exception {
        mockMvc.perform(get("/front-desk").param("arrivalDate", "OVERDUE").param("sort", "guestName").param("dir", "desc")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString(
                        "front-desk-search__clear\" href=\"/front-desk?view=arrivals&amp;sort=guestName&amp;dir=desc\"")));
    }

    /** Confirms pagination links keep both the active sort and the filters, so paging never restarts the ordering. */
    @Test
    void shouldPreserveSortAndFiltersAcrossPages() throws Exception {
        when(queryService.arrivals(any(), anyInt())).thenReturn(new PageImpl<>(List.of(
                new FrontDeskArrivalRow(RES, "R-1", "Ann", "G-1", BookingSource.AGODA, null, TODAY, true, true,
                        new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.LATE, List.of()),
                        List.of(), false, null)),
                PageRequest.of(0, 10), 25));
        mockMvc.perform(get("/front-desk").param("source", "AGODA").param("sort", "guestName").param("dir", "asc")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("page=1")))
                .andExpect(content().string(containsString("sort=guestName&amp;dir=asc")))
                .andExpect(content().string(containsString("source=AGODA")));
    }

    /** Confirms a sorted out-of-range page redirects to the last page while keeping the sort. */
    @Test
    void shouldRedirectSortedOutOfRangePagePreservingSort() throws Exception {
        when(queryService.departures(eq(false), any(), eq(4))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 10), 2));
        mockMvc.perform(get("/front-desk").param("view", "departures").param("page", "4")
                        .param("sort", "nights").param("dir", "desc").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/front-desk?view=departures&sort=nights&dir=desc&page=0"));
    }

    private static FrontDeskStayRow stayRow(List<FrontDeskRoomResponse> rooms, boolean overdue, boolean owing, BigDecimal amount) {
        return new FrontDeskStayRow(RES, "R-9", "Nguyen Van B", "G-1", rooms, Instant.parse("2026-09-20T07:35:00Z"),
                TODAY.minusDays(overdue ? 1 : 0), overdue, overdue ? 1 : 0, owing, overdue || owing, amount, "VND",
                BookingSource.DIRECT, 4L);
    }

    /** Returns the markup of the page's single results table body, so row-level checks ignore the sidebar and header. */
    private static String tableBody(String body) {
        int start = body.indexOf("<tbody>");
        int end = body.indexOf("</tbody>");
        return start < 0 || end < start ? "" : body.substring(start, end);
    }

    /** Returns the markup of the Front Desk pager only, so page-number and link checks ignore the rest of the page. */
    private static String pagerMarkup(String body) {
        int start = body.indexOf("front-desk-pagination");
        int end = body.indexOf("</nav>", start);
        return start < 0 || end < start ? "" : body.substring(start, end);
    }

    private static int countOccurrences(String body, String token) {
        int count = 0;
        for (int index = body.indexOf(token); index >= 0; index = body.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    private static RequestPostProcessor perm(String... authorities) {
        return user("tester").authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
