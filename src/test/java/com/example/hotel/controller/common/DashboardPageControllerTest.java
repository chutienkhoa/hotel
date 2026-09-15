package com.example.hotel.controller.common;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DashboardService;
import java.util.List;
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

/** Verifies Dashboard MVC authorization and its read-only route contract. */
@WebMvcTest(DashboardPageController.class)
@Import(DashboardPageControllerTest.MethodSecurityTestConfiguration.class)
class DashboardPageControllerTest {

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
        when(dashboardService.getDashboard()).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard")
                        .with(user(username).authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"))
                .andExpect(model().attribute("canViewReport", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "app-shell page-dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("nav-dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reservations")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This year")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This month")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SEP 2026")));
    }

    /** Verifies STAFF cannot access the Dashboard without VIEW_REPORT. */
    @org.junit.jupiter.api.Test
    void shouldRejectStaffWithoutViewReport() throws Exception {
        mockMvc.perform(get("/dashboard")
                        .with(user("staff").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Verifies the Dashboard route exposes no state-changing POST operation. */
    @org.junit.jupiter.api.Test
    void shouldExposeDashboardAsReadOnlyRoute() throws Exception {
        mockMvc.perform(post("/dashboard")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT")))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Creates the empty approved Dashboard shape used by MVC controller tests. */
    private DashboardResponse emptyDashboard() {
        return new DashboardResponse(
                0L, 0L, "SEP 2026", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Enables method-security interception for Dashboard MVC tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
