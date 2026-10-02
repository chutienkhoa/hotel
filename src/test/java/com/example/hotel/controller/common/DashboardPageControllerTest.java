package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.dto.common.response.DashboardArrivalsKpi;
import com.example.hotel.dto.common.response.DashboardCurrentlyStayingKpi;
import com.example.hotel.dto.common.response.DashboardDeparturesKpi;
import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.dto.common.response.DashboardStatusCountResponse;
import com.example.hotel.dto.common.response.DashboardStayRow;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DashboardService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the final Task33 Dashboard's MVC authorization, per-block permission gating, and content. */
@WebMvcTest(DashboardPageController.class)
@Import(DashboardPageControllerTest.MethodSecurityTestConfiguration.class)
class DashboardPageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ROOM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private JwtService jwtService;

    /** Verifies ADMIN and MANAGER can access the Dashboard through VIEW_REPORT. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "manager"})
    void shouldAllowRolesWithViewReport(String username) throws Exception {
        when(dashboardService.getDashboard(any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard")
                        .with(user(username).authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"))
                .andExpect(model().attribute("canViewReport", true))
                .andExpect(content().string(containsString("app-shell page-dashboard")))
                .andExpect(content().string(containsString("nav-dashboard")))
                .andExpect(content().string(containsString("Welcome back, " + username + "!")))
                .andExpect(content().string(containsString("Tuesday, Sep 16, 2026")))
                .andExpect(content().string(containsString("Available Rooms")))
                .andExpect(content().string(containsString("Room Status")));
    }

    /** Verifies every permission-gated operational block renders its real content for a fully permitted viewer. */
    @Test
    void shouldShowAllOperationalBlocksForFullyPermittedViewer() throws Exception {
        when(dashboardService.getDashboard(any())).thenReturn(fullDashboard());

        mockMvc.perform(get("/dashboard")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_REPORT"),
                                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"),
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"),
                                new SimpleGrantedAuthority("PERM_MANAGE_ROOM"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Currently Staying")))
                .andExpect(content().string(containsString(">12</span> <span class=\"kpi-card__unit\">guests<")))
                .andExpect(content().string(containsString("in 7 rooms")))
                .andExpect(content().string(containsString("+2 vs yesterday")))
                .andExpect(content().string(containsString("Today&#39;s Arrivals")))
                .andExpect(content().string(containsString("RSV-0001")))
                .andExpect(content().string(containsString("Today&#39;s Departures")))
                .andExpect(content().string(containsString("Recent Reservations")))
                .andExpect(content().string(containsString("RSV-0002")))
                .andExpect(content().string(containsString("Quick Actions")))
                .andExpect(content().string(containsString(">New Reservation<")))
                .andExpect(content().string(containsString(">Guest Management<")))
                .andExpect(content().string(containsString(">Room Management<")))
                .andExpect(content().string(containsString(">View Reports<")))
                .andExpect(content().string(containsString("href=\"/check-in/reservations/" + RESERVATION_ID + "\"")))
                .andExpect(content().string(containsString("href=\"/check-out/" + RESERVATION_ID + "\"")));
    }

    /** Verifies a VIEW_REPORT-only viewer never sees operational/guest-identifying blocks (no leak, no disabled render). */
    @Test
    void shouldOmitOperationalBlocksForViewReportOnlyViewer() throws Exception {
        when(dashboardService.getDashboard(any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard")
                        .with(user("owner-view-only").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Currently Staying"))))
                .andExpect(content().string(not(containsString("Today&#39;s Arrivals"))))
                .andExpect(content().string(not(containsString("Today&#39;s Departures"))))
                .andExpect(content().string(not(containsString("Recent Reservations"))))
                .andExpect(content().string(not(containsString(">New Reservation<"))))
                .andExpect(content().string(not(containsString(">Guest Management<"))))
                .andExpect(content().string(not(containsString(">Room Management<"))))
                .andExpect(content().string(containsString("Available Rooms")))
                .andExpect(content().string(containsString("Room Status")))
                .andExpect(content().string(containsString(">View Reports<")));
    }

    /** Verifies the final Dashboard never reproduces mockup-only fields/statuses or the retired analytics layout. */
    @Test
    void shouldNotRenderInventedOrRemovedContent() throws Exception {
        when(dashboardService.getDashboard(any())).thenReturn(fullDashboard());

        mockMvc.perform(get("/dashboard")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_REPORT"),
                                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("ETA"))))
                .andExpect(content().string(not(containsString(">Expected<"))))
                .andExpect(content().string(not(containsString(">Due today<"))))
                .andExpect(content().string(not(containsString(">Pending<"))))
                .andExpect(content().string(not(containsString("Reservations by Check-in Month"))))
                .andExpect(content().string(not(containsString("Booked Rooms by RoomType"))))
                .andExpect(content().string(not(containsString("Reservations by Source"))))
                .andExpect(content().string(not(containsString("Expenses by Status"))))
                .andExpect(content().string(not(containsString("This year"))))
                .andExpect(content().string(not(containsString("This month"))));
    }

    /** Verifies the Dashboard renders fully translated Vietnamese text when the Vietnamese locale is selected. */
    @Test
    void shouldRenderVietnameseTranslations() throws Exception {
        when(dashboardService.getDashboard(any())).thenReturn(fullDashboard());

        mockMvc.perform(get("/dashboard")
                        .param("lang", "vi")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_REPORT"),
                                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chào mừng trở lại, admin!")))
                .andExpect(content().string(containsString("Đang lưu trú")))
                .andExpect(content().string(containsString("Trạng thái phòng")))
                .andExpect(content().string(containsString("Đặt phòng gần đây")))
                .andExpect(content().string(containsString("Thao tác nhanh")));
    }

    /** Verifies the application root redirects to the Dashboard without loading any Dashboard data. */
    @Test
    void shouldRedirectRootToDashboard() throws Exception {
        mockMvc.perform(get("/").with(user("admin").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
        org.mockito.Mockito.verifyNoInteractions(dashboardService);
    }

    /** Verifies STAFF cannot access the Dashboard without VIEW_REPORT. */
    @Test
    void shouldRejectStaffWithoutViewReport() throws Exception {
        mockMvc.perform(get("/dashboard")
                        .with(user("staff").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Verifies the Dashboard route exposes no state-changing POST operation. */
    @Test
    void shouldExposeDashboardAsReadOnlyRoute() throws Exception {
        mockMvc.perform(post("/dashboard")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT")))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Creates the Dashboard shape for a viewer with no operational permission beyond VIEW_REPORT. */
    private DashboardResponse emptyDashboard() {
        return new DashboardResponse(
                LocalDate.of(2026, 9, 16),
                "Tuesday, Sep 16, 2026",
                null,
                6L,
                null,
                null,
                List.of(),
                roomStatusCounts(),
                20L,
                List.of(),
                List.of(),
                List.of());
    }

    /** Creates a fully populated Dashboard shape with one row per operational block. */
    private DashboardResponse fullDashboard() {
        FrontDeskRoomResponse room = new FrontDeskRoomResponse(ROOM_ID, "101", "Double Room", null);
        ArrivalReadiness readiness = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of());
        FrontDeskArrivalRow arrivalRow = new FrontDeskArrivalRow(
                RESERVATION_ID, "RSV-0001", "John Smith", "G-0001", BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 16), false, false, readiness, List.of(room), false, "0900000000");
        FrontDeskStayRow stayRow = new FrontDeskStayRow(
                RESERVATION_ID, "RSV-0003", "James Brown", "G-0003", List.of(room),
                Instant.parse("2026-09-14T07:00:00Z"), LocalDate.of(2026, 9, 18), false, 0L, false, false, null, "VND");
        FrontDeskStayRow departureRow = new FrontDeskStayRow(
                RESERVATION_ID, "RSV-0004", "William Clark", "G-0004", List.of(room),
                Instant.parse("2026-09-13T07:00:00Z"), LocalDate.of(2026, 9, 16), false, 0L, false, false, null, "VND");
        ReservationSummaryResponse recent = new ReservationSummaryResponse(
                RESERVATION_ID, "RSV-0002", "Emma Wilson", "102", "CONFIRMED", BookingSource.BOOKING_COM, null,
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), null, "VND");

        return new DashboardResponse(
                LocalDate.of(2026, 9, 16),
                "Tuesday, Sep 16, 2026",
                new DashboardCurrentlyStayingKpi(12L, 7L, 2L),
                6L,
                new DashboardArrivalsKpi(1L, 0L),
                new DashboardDeparturesKpi(1L, 0L),
                List.of(arrivalRow),
                roomStatusCounts(),
                20L,
                List.of(new DashboardStayRow(stayRow, 2L)),
                List.of(new DashboardStayRow(departureRow, 3L)),
                List.of(recent));
    }

    /** Builds a complete, zero-filled 6-status Room Status series for test fixtures. */
    private List<DashboardStatusCountResponse> roomStatusCounts() {
        return List.of(
                new DashboardStatusCountResponse("AVAILABLE", 6L),
                new DashboardStatusCountResponse("OCCUPIED", 12L),
                new DashboardStatusCountResponse("DIRTY", 1L),
                new DashboardStatusCountResponse("CLEANING", 1L),
                new DashboardStatusCountResponse("MAINTENANCE", 0L),
                new DashboardStatusCountResponse("OUT_OF_ORDER", 0L));
    }

    /** Enables method-security interception for Dashboard MVC tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
