package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the Reports Overview: authorization, sidebar entry, informational cards, and EN/VI text. */
@WebMvcTest(ReportPageController.class)
@Import({ReportPageControllerTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class ReportPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reportViewer() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    /** Confirms a user with VIEW_REPORT can open the overview, which marks the Reports nav item active. */
    @Test
    void shouldAllowUserWithViewReport() throws Exception {
        mockMvc.perform(get("/reports").with(reportViewer()))
                .andExpect(status().isOk())
                .andExpect(view().name("report/overview"))
                .andExpect(content().string(containsString("app-shell page-reports")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reports\"")))
                .andExpect(content().string(containsString("href=\"/reports\"")));
    }

    /** Confirms a user without VIEW_REPORT (the STAFF role) is denied. */
    @Test
    void shouldDenyUserWithoutViewReport() throws Exception {
        mockMvc.perform(get("/reports").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"), new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms unauthenticated access is rejected. */
    @Test
    void shouldRejectAnonymousAccess() throws Exception {
        mockMvc.perform(get("/reports")).andExpect(status().is4xxClientError());
    }

    /** Confirms the Financial card now links to its report and the Occupancy card stays informational. */
    @Test
    void shouldLinkFinancialCardAndKeepOccupancyComingSoon() throws Exception {
        String body = mockMvc.perform(get("/reports").cookie(new Cookie("pms-lang", "en")).with(reportViewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"report-financial\"")))
                .andExpect(content().string(containsString("id=\"report-occupancy\"")))
                .andExpect(content().string(containsString("href=\"/reports/monthly-financial\"")))
                .andReturn().getResponse().getContentAsString();

        String occupancyCard = body.substring(body.indexOf("id=\"report-occupancy\""));
        org.junit.jupiter.api.Assertions.assertTrue(occupancyCard.contains("Coming soon"));
        org.junit.jupiter.api.Assertions.assertFalse(occupancyCard.contains("<a "), "occupancy card has no link");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("monthly-occupancy"));
        org.junit.jupiter.api.Assertions.assertEquals(1, body.split("monthly-financial", -1).length - 1);
    }

    /** Confirms the English rendering. */
    @Test
    void shouldRenderEnglish() throws Exception {
        mockMvc.perform(get("/reports").cookie(new Cookie("pms-lang", "en")).with(reportViewer()))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Monthly Financial Report")))
                .andExpect(content().string(containsString("Revenue, expenses and profitability for a selected month.")))
                .andExpect(content().string(containsString("Monthly Occupancy Report")))
                .andExpect(content().string(containsString("Room-night occupancy and hotel utilization for a selected month.")))
                .andExpect(content().string(containsString("Coming soon")))
                .andExpect(content().string(containsString("<title>Reports | Hotel Management</title>")));
    }

    /** Confirms the Vietnamese rendering, including the sidebar entry. */
    @Test
    void shouldRenderVietnamese() throws Exception {
        mockMvc.perform(get("/reports").cookie(new Cookie("pms-lang", "vi")).with(reportViewer()))
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andExpect(content().string(containsString("Báo cáo tài chính tháng")))
                .andExpect(content().string(containsString("Báo cáo công suất phòng tháng")))
                .andExpect(content().string(containsString("Sắp có")))
                .andExpect(content().string(containsString("<span>Báo cáo</span>")))
                .andExpect(content().string(not(containsString("Coming soon"))));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
