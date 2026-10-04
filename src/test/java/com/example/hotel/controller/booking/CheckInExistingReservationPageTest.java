package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
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

/** Verifies the Existing Reservation page: columns, OTA display, links, filters, sort and pagination wiring, EN/VI, permissions. */
@WebMvcTest(value = CheckInPageController.class, properties = "hotel.i18n.default-locale=en")
@Import({CheckInExistingReservationPageTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class CheckInExistingReservationPageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);
    private static final UUID AGODA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DIRECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private CheckInService checkInService;

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

    /** Stubs an overdue ready OTA reservation and a needs-attention DIRECT one. */
    @BeforeEach
    void stubData() {
        when(frontDeskQueryService.hotelToday()).thenReturn(TODAY);
        when(frontDeskQueryService.confirmedReservations(any(), anyInt())).thenReturn(page(2, 0));
    }

    private static PageImpl<FrontDeskArrivalRow> page(long total, int number) {
        ArrivalReadiness ready = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.LATE, List.of());
        ArrivalReadiness needs = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.EARLY, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ARRIVAL_TOO_EARLY, null)));
        List<FrontDeskArrivalRow> content = new ArrayList<>(List.of(
                new FrontDeskArrivalRow(AGODA_ID, "R20261004-000003", "Chu Khoa", "G000004", BookingSource.AGODA, "123456789",
                        TODAY.minusDays(1), true, true, ready,
                        List.of(new FrontDeskRoomResponse(UUID.randomUUID(), "DEMO-304", "Twin Room", null)), false, null, 4L, 1L),
                new FrontDeskArrivalRow(DIRECT_ID, "R20260930-000012", "Mai Vu", "G000012", BookingSource.DIRECT, null,
                        TODAY.plusDays(1), false, true, needs,
                        List.of(new FrontDeskRoomResponse(UUID.randomUUID(), "DEMO-205", "Double Room", null)), false, null, 2L, 0L)));
        return new PageImpl<>(content, PageRequest.of(number, 10), total);
    }

    /** Confirms the page renders the approved header, filters, results heading and the exact column order. */
    @Test
    void shouldRenderApprovedLayoutInEnglish() throws Exception {
        String body = mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN", "PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Breadcrumb\"")))
                .andExpect(content().string(containsString("<a href=\"/front-desk\">Front Desk</a>")))
                .andExpect(content().string(containsString("<a href=\"/check-in\">Check-in</a>")))
                .andExpect(content().string(containsString("breadcrumb-current\">Find Reservation</span>")))
                .andExpect(content().string(not(containsString("Back to Check-in Guest"))))
                .andExpect(content().string(containsString("Existing Reservation")))
                .andExpect(content().string(containsString("Find a confirmed reservation and continue to Check-in Review.")))
                .andExpect(content().string(containsString("Guest, reservation, room, or OTA reference...")))
                .andExpect(content().string(containsString("All dates")))
                .andExpect(content().string(containsString("All status")))
                .andExpect(content().string(containsString("All sources")))
                .andExpect(content().string(containsString("Search Results")))
                .andExpect(content().string(containsString("Showing 1–2 of 2 reservations")))
                .andReturn().getResponse().getContentAsString();
        // The filter order is Search, Arrival date, Readiness, Source, Clear.
        assertInOrder(body, "name=\"search\"", "name=\"arrivalDate\"", "name=\"readiness\"", "name=\"source\"", "front-desk-search__clear");
        // Source options are the real booking sources.
        assertInOrder(body, ">Direct<", ">Agoda<", ">Booking.com<", ">Airbnb<");
        String head = body.substring(body.indexOf("<thead>"), body.indexOf("</thead>"));
        assertInOrder(head, "Reservation No.", "Guest", "Arrival Date", "Room(s)", "Nights", "Source", "OTA Booking", "Status", "Action");
        // No Arrivals / In-house / Departures tabs belong to this screen.
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("front-desk-tabs"));
    }

    /** Confirms every column but Action is a sort link, and Action is a plain, non-sortable header. */
    @Test
    void shouldMakeEveryColumnExceptActionSortable() throws Exception {
        String body = mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN"))).andReturn().getResponse().getContentAsString();
        for (String key : List.of("reservationNumber", "guestName", "checkInDate", "room", "nights", "source", "otaBookingReference", "readiness")) {
            org.junit.jupiter.api.Assertions.assertTrue(body.contains("href=\"/check-in/existing?sort=" + key + "&amp;dir=asc\""), key);
        }
        String head = body.substring(body.indexOf("<thead>"), body.indexOf("</thead>"));
        org.junit.jupiter.api.Assertions.assertEquals(8, countOccurrences(head, "front-desk-sort\""));
        org.junit.jupiter.api.Assertions.assertTrue(head.contains("<th class=\"table-action-column\" scope=\"col\">Action</th>"));
    }

    /** Confirms OTA rows show the actual reference, DIRECT rows show a dash, and overdue days show under the date. */
    @Test
    void shouldShowOtaReferenceDashAndOverdueIndicator() throws Exception {
        String body = tbody(mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN"))).andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("<td>123456789</td>"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("<td>—</td>"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("1 day overdue"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("04/10/2026") || body.contains("03/10/2026"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("DEMO-304") && body.contains("Twin Room"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("G000004"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Ready for Check-in"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Needs attention"));
    }

    /** Confirms the Check-in button is a GET link to the canonical Review, never a form/POST, and no Select label remains. */
    @Test
    void shouldLinkCheckInToReviewWithoutPerformingCheckIn() throws Exception {
        String body = mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN"))).andReturn().getResponse().getContentAsString();
        String rows = tbody(body);
        org.junit.jupiter.api.Assertions.assertTrue(rows.contains("href=\"/check-in/reservations/" + AGODA_ID + "?from=find\""));
        org.junit.jupiter.api.Assertions.assertTrue(rows.contains("href=\"/check-in/reservations/" + DIRECT_ID + "?from=find\""));
        org.junit.jupiter.api.Assertions.assertFalse(rows.contains("<form"));
        org.junit.jupiter.api.Assertions.assertFalse(rows.contains("Select"));
        org.junit.jupiter.api.Assertions.assertFalse(rows.contains("/confirm"));
        verify(checkInService, never()).confirmCheckIn(any());
        verifyNoInteractions(prepaymentService);
    }

    /** Confirms the Reservation Number links to Reservation Detail only for a viewer who may open it (no dead link). */
    @Test
    void shouldLinkReservationNumberToDetailOnlyWithViewBooking() throws Exception {
        String withView = tbody(mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN", "PERM_VIEW_BOOKING")))
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(withView.contains("href=\"/reservations/" + AGODA_ID + "?from=find\""));
        String without = tbody(mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN")))
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertFalse(without.contains("href=\"/reservations/"));
        org.junit.jupiter.api.Assertions.assertTrue(without.contains("R20261004-000003"));
    }

    /** Confirms search, date, readiness, source and sort reach the read model together, and are echoed in sort links. */
    @Test
    void shouldPassCombinedFiltersAndSortToTheReadModelAndPreserveThem() throws Exception {
        String body = mockMvc.perform(get("/check-in/existing").param("search", " 123456789 ").param("arrivalDate", "OVERDUE")
                        .param("readiness", "READY").param("source", "AGODA").param("sort", "otaBookingReference").param("dir", "desc")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        verify(frontDeskQueryService).confirmedReservations(argThat((FrontDeskSearchCriteria c) ->
                "123456789".equals(c.getSearch()) && "OVERDUE".equals(c.getArrivalDate()) && "READY".equals(c.getReadiness())
                        && "AGODA".equals(c.getSource()) && "otaBookingReference".equals(c.getSort())), eq(0));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(
                "href=\"/check-in/existing?search=123456789&amp;arrivalDate=OVERDUE&amp;readiness=READY&amp;source=AGODA&amp;sort=nights&amp;dir=asc\""));
        // Clear drops every filter and the page, keeping only the active sort.
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("href=\"/check-in/existing?sort=otaBookingReference&amp;dir=desc\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"123456789\""));
    }

    /** Confirms pagination sits in the results header, shows the current page, and keeps filters and sort in its links. */
    @Test
    void shouldPaginateAboveTheTableKeepingFiltersAndSort() throws Exception {
        when(frontDeskQueryService.confirmedReservations(any(), eq(1))).thenReturn(page(29, 1));
        String body = mockMvc.perform(get("/check-in/existing").param("page", "1").param("source", "AGODA").param("sort", "nights").param("dir", "asc")
                        .with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 11–12 of 29 reservations")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.indexOf("front-desk-pagination") < body.indexOf("<table"));
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(body, "front-desk-pagination\""), "single pager only");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("href=\"/check-in/existing?source=AGODA&amp;sort=nights&amp;dir=asc&amp;page=2\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("aria-current=\"page\" class=\"pagination__segment pagination__segment--current\">2<"));
    }

    /** Confirms a page past the end redirects to the last valid page with filters and sort preserved. */
    @Test
    void shouldRedirectOutOfRangePageKeepingState() throws Exception {
        when(frontDeskQueryService.confirmedReservations(any(), eq(9))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 10), 29));
        mockMvc.perform(get("/check-in/existing").param("page", "9").param("source", "AGODA").with(perm("PERM_CHECK_IN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/existing?source=AGODA&page=2"));
    }

    /** Confirms the empty states with and without active filters. */
    @Test
    void shouldExplainEmptyResults() throws Exception {
        when(frontDeskQueryService.confirmedReservations(any(), anyInt())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("No confirmed reservations are waiting for check-in.")))
                .andExpect(content().string(containsString("0 results")));
        mockMvc.perform(get("/check-in/existing").param("search", "zzz").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("No reservations match your search.")));
    }

    /** Confirms the Vietnamese rendering uses translated text throughout and no leftover English UI strings. */
    @Test
    void shouldRenderInVietnamese() throws Exception {
        mockMvc.perform(get("/check-in/existing").with(perm("PERM_CHECK_IN", "PERM_VIEW_BOOKING")).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("breadcrumb-current\">Tìm đặt phòng</span>")))
                .andExpect(content().string(containsString("Đặt phòng có sẵn")))
                .andExpect(content().string(containsString("Kết quả tìm kiếm")))
                .andExpect(content().string(containsString("Hiển thị 1–2 trong 2 đặt phòng")))
                .andExpect(content().string(containsString("Mã OTA")))
                .andExpect(content().string(containsString("Sẵn sàng nhận phòng")))
                .andExpect(content().string(containsString("Cần xử lý")))
                .andExpect(content().string(containsString("Quá hạn 1 ngày")))
                .andExpect(content().string(not(containsString("Search Results"))))
                .andExpect(content().string(not(containsString("Find a confirmed reservation"))));
    }

    /** Confirms the page needs CHECK_IN: another permission, such as VIEW_BOOKING, is refused. */
    @Test
    void shouldRefuseUserWithoutCheckIn() throws Exception {
        mockMvc.perform(get("/check-in/existing").with(perm("PERM_VIEW_BOOKING", "PERM_CHECK_OUT")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(frontDeskQueryService);
    }

    private static void assertInOrder(String text, String... tokens) {
        int from = 0;
        for (String token : tokens) {
            int index = text.indexOf(token, from);
            org.junit.jupiter.api.Assertions.assertTrue(index >= 0, "missing or out of order: " + token);
            from = index + token.length();
        }
    }

    private static String tbody(String body) {
        return body.substring(body.indexOf("<tbody>"), body.indexOf("</tbody>"));
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
