package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.NonVndRoomRevenueWarning;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.MonthlyFinancialReportService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the Monthly Financial Report page: authorization, month handling, rendering, and EN/VI text. */
@WebMvcTest(MonthlyFinancialReportPageController.class)
@Import({MonthlyFinancialReportPageControllerTest.TestConfig.class, I18nConfig.class})
class MonthlyFinancialReportPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyFinancialReportService reportService;

    @MockitoBean
    private JwtService jwtService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    private static MonthlyFinancialReport report(YearMonth month, BigDecimal margin, NonVndRoomRevenueWarning warning) {
        return new MonthlyFinancialReport(month, month.atDay(1), month.plusMonths(1).atDay(1), "VND",
                new BigDecimal("3000000"), new BigDecimal("1000000"), new BigDecimal("4000000"),
                new BigDecimal("1000000"), new BigDecimal("3000000"), margin, warning);
    }

    /** Confirms a user with VIEW_REPORT gets 200, and the Reports sidebar item stays active. */
    @Test
    void shouldAllowViewReportAndKeepReportsNavigationActive() throws Exception {
        when(reportService.report(any())).thenReturn(report(YearMonth.of(2026, 9), new BigDecimal("75.00"), null));

        mockMvc.perform(get("/reports/monthly-financial").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reports")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reports\"")));
    }

    /** Confirms users without VIEW_REPORT are forbidden and anonymous users are rejected. */
    @Test
    void shouldDenyWithoutPermissionAndRejectAnonymous() throws Exception {
        mockMvc.perform(get("/reports/monthly-financial").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/monthly-financial")).andExpect(status().is4xxClientError());
        verify(reportService, never()).report(any());
    }

    /** Confirms an omitted month uses the current hotel month from the injected Clock. */
    @Test
    void shouldDefaultToCurrentHotelMonth() throws Exception {
        when(reportService.report(any())).thenReturn(report(YearMonth.of(2026, 9), new BigDecimal("75.00"), null));

        mockMvc.perform(get("/reports/monthly-financial").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("type=\"month\"")))
                .andExpect(content().string(containsString("value=\"2026-09\"")));

        verify(reportService).report(YearMonth.of(2026, 9));
    }

    /** Confirms a valid month parameter is used, and the month input keeps the ISO yyyy-MM value. */
    @Test
    void shouldReportRequestedMonth() throws Exception {
        when(reportService.report(YearMonth.of(2026, 10))).thenReturn(report(YearMonth.of(2026, 10), new BigDecimal("75.00"), null));

        mockMvc.perform(get("/reports/monthly-financial").param("month", "2026-10").cookie(language("vi")).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2026-10\"")));

        verify(reportService).report(YearMonth.of(2026, 10));
    }

    /** Confirms an invalid month shows a friendly message, runs no report, and never returns a 500. */
    @Test
    void shouldHandleInvalidMonthFriendly() throws Exception {
        for (String invalid : List.of("2026-13", "abc", "2026-9", "20260", "2026-00", "../x", "+12345-01")) {
            mockMvc.perform(get("/reports/monthly-financial").param("month", invalid).cookie(language("en")).with(viewer()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Please choose a valid month (yyyy-MM).")))
                    .andExpect(content().string(containsString("value=\"2026-09\"")));
        }
        verify(reportService, never()).report(any());
    }

    /** Confirms an integrity failure shows a translated message without exception details, with HTTP 500. */
    @Test
    void shouldShowFriendlyMessageOnIntegrityFailure() throws Exception {
        when(reportService.report(any())).thenThrow(new ReportDataIntegrityException(UUID.randomUUID(), "SECRET internal detail"));

        mockMvc.perform(get("/reports/monthly-financial").cookie(language("en")).with(viewer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("cannot be produced")))
                .andExpect(content().string(not(containsString("SECRET"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    /** Confirms English rendering, including the margin and the non-VND warning. */
    @Test
    void shouldRenderEnglish() throws Exception {
        when(reportService.report(any())).thenReturn(report(YearMonth.of(2026, 9), new BigDecimal("75.00"),
                new NonVndRoomRevenueWarning(2, 3, List.of("EUR", "USD"))));

        mockMvc.perform(get("/reports/monthly-financial").cookie(language("en")).with(viewer()))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Monthly Financial Report")))
                .andExpect(content().string(containsString("Room Revenue")))
                .andExpect(content().string(containsString("Additional Revenue")))
                .andExpect(content().string(containsString("Total Revenue")))
                .andExpect(content().string(containsString("Expenses")))
                .andExpect(content().string(containsString("Net Profit")))
                .andExpect(content().string(containsString("Profit Margin")))
                .andExpect(content().string(containsString("3,000,000 VND")))
                .andExpect(content().string(containsString("75.00%")))
                .andExpect(content().string(containsString("2 reservation(s), 3 room booking(s), currencies: EUR, USD")));
    }

    /** Confirms Vietnamese rendering and the N/A margin when revenue is zero. */
    @Test
    void shouldRenderVietnameseWithMarginNotApplicable() throws Exception {
        when(reportService.report(any())).thenReturn(report(YearMonth.of(2026, 9), null, null));

        mockMvc.perform(get("/reports/monthly-financial").cookie(language("vi")).with(viewer()))
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andExpect(content().string(containsString("Báo cáo tài chính tháng")))
                .andExpect(content().string(containsString("Doanh thu phòng")))
                .andExpect(content().string(containsString("Tổng doanh thu")))
                .andExpect(content().string(containsString("Lợi nhuận ròng")))
                .andExpect(content().string(containsString("Không áp dụng")))
                .andExpect(content().string(not(containsString("Room Revenue"))))
                .andExpect(content().string(not(containsString("class=\"message message-warning\""))));
    }

    /** Enables method security and supplies a fixed hotel Clock. */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(
                    LocalDate.of(2026, 9, 18).atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
