package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DashboardService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Task33 Batch 1A app-shell foundation: the renamed brand, the header's mobile nav toggle
 * and account dropdown, the mobile backdrop, and that the sidebar items left deliberately unchanged
 * (Rooms, Housekeeping, the transitional Check-in/Check-out) still render. Uses {@code /dashboard}
 * purely as a cheap real page to render the shared shell fragments against, the same pattern
 * {@link LogoutSecurityTest} and {@link DashboardPageControllerTest} already use.
 */
@WebMvcTest(DashboardPageController.class)
@Import(AppShellTest.MethodSecurityTestConfiguration.class)
class AppShellTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private JwtService jwtService;

    /** Grants every navigation-relevant authority, so the whole shell renders fully for inspection. */
    private static List<SimpleGrantedAuthority> everyNavigationAuthority() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_REPORT"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"),
                new SimpleGrantedAuthority("PERM_MANAGE_ROOM"),
                new SimpleGrantedAuthority("PERM_MANAGE_HOUSEKEEPING"),
                new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"),
                new SimpleGrantedAuthority("PERM_MANAGE_ADDITIONAL_REVENUE"),
                new SimpleGrantedAuthority("PERM_MANAGE_STAFF"),
                new SimpleGrantedAuthority("PERM_MANAGE_USER"));
    }

    /** Confirms the renamed brand appears in both the sidebar and the header. */
    @Test
    void shouldRenderSunsetHouseBrandInSidebarAndHeader() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-brand-name\">Sunset House<")))
                .andExpect(content().string(containsString("class=\"brand\"")))
                .andExpect(content().string(containsString(">Sunset House</a>")))
                .andExpect(content().string(not(containsString("Hotel Management"))))
                .andExpect(content().string(not(containsString("sidebar-brand-tagline"))));
    }

    /** Confirms the mobile nav toggle targets the sidebar and starts closed, with a localized label. */
    @Test
    void shouldRenderMobileNavToggleControllingTheSidebar() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"header-nav-toggle\"")))
                .andExpect(content().string(containsString("aria-controls=\"sidebar-nav\"")))
                .andExpect(content().string(containsString("aria-expanded=\"false\"")))
                .andExpect(content().string(containsString("aria-label=\"Open navigation menu\"")))
                .andExpect(content().string(containsString("data-label-open=\"Open navigation menu\"")))
                .andExpect(content().string(containsString("data-label-close=\"Close navigation menu\"")))
                .andExpect(content().string(containsString("id=\"sidebar-nav\"")))
                .andExpect(content().string(containsString("id=\"sidebar-backdrop\"")));
    }

    /** Confirms the header account dropdown exposes the username, language switcher, and logout form. */
    @Test
    void shouldRenderHeaderAccountDropdown() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("an.le").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"header-account-trigger\"")))
                .andExpect(content().string(containsString("aria-haspopup=\"menu\"")))
                .andExpect(content().string(containsString("aria-controls=\"header-account-menu\"")))
                .andExpect(content().string(containsString("class=\"header-account-name\">an.le<")))
                .andExpect(content().string(containsString("class=\"header-account-initial\">A<")))
                .andExpect(content().string(containsString("id=\"header-account-menu\"")))
                // Bare boolean `hidden` (not hidden="hidden"), so it never collides with the Reservation
                // form's own conditional hidden="hidden" OTA-reference-field toggling elsewhere on the page.
                .andExpect(content().string(containsString("class=\"header-account-menu\" hidden")))
                .andExpect(content().string(containsString("class=\"language-switch\"")))
                .andExpect(content().string(containsString("action=\"/logout\"")));
    }

    /**
     * Confirms the transitional Check-in/Check-out links Batch 1A left unchanged still render, and that
     * Batch 1B's final HOTEL nesting is in place: "Rooms" is a non-link group label (no {@code
     * sidebar-nav-link} class, per spec sec. 7.1a) with Room List/Housekeeping as its linked children.
     */
    @Test
    void shouldStillRenderUnchangedGroupsAndTransitionalLinks() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"nav-group-label\"")))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-rooms\""))))
                .andExpect(content().string(containsString("nav-room-list")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link sidebar-nav-link--nested nav-housekeeping\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-check-in\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-check-out\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-staff\"")));
    }

    /** Creates the empty approved Dashboard shape used by MVC controller tests. */
    private DashboardResponse emptyDashboard() {
        return new DashboardResponse(
                0L, 0L, "SEP 2026", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Enables method-security interception for shell MVC tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
