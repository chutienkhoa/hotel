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
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.MonthlyOccupancyReportService;
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

/** Verifies the Monthly Occupancy Report page: authorization, month handling, error messages, rendering, EN/VI. */
@WebMvcTest(MonthlyOccupancyReportPageController.class)
@Import({MonthlyOccupancyReportPageControllerTest.TestConfig.class, I18nConfig.class})
class MonthlyOccupancyReportPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyOccupancyReportService reportService;

    @MockitoBean
    private JwtService jwtService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    private static MonthlyOccupancyReport completedMonth(BigDecimal rate) {
        YearMonth month = YearMonth.of(2026, 8);
        return new MonthlyOccupancyReport(month, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 31), 32, 60, rate,
                List.of(new RoomTypeOccupancy(UUID.randomUUID(), "DOUBLE", "Double", 32, 60, rate)));
    }

    private static MonthlyOccupancyReport currentMonth() {
        return new MonthlyOccupancyReport(YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 17), 5, 34,
                new BigDecimal("14.71"), List.of());
    }

    /** Confirms VIEW_REPORT gets 200 and the single Reports sidebar item stays active. */
    @Test
    void shouldAllowViewReportAndKeepReportsNavigationActive() throws Exception {
        when(reportService.report(any())).thenReturn(completedMonth(new BigDecimal("53.33")));

        mockMvc.perform(get("/reports/monthly-occupancy").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reports")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reports\"")));
    }

    /** Confirms users without VIEW_REPORT are forbidden and anonymous users rejected. */
    @Test
    void shouldDenyWithoutPermissionAndRejectAnonymous() throws Exception {
        mockMvc.perform(get("/reports/monthly-occupancy").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/monthly-occupancy")).andExpect(status().is4xxClientError());
        verify(reportService, never()).report(any());
    }

    /** Confirms an omitted month uses the current hotel month; a given month is used and kept as ISO. */
    @Test
    void shouldDefaultToCurrentHotelMonthAndUseRequestedMonth() throws Exception {
        when(reportService.report(any())).thenReturn(currentMonth());

        mockMvc.perform(get("/reports/monthly-occupancy").with(viewer()))
                .andExpect(content().string(containsString("type=\"month\"")))
                .andExpect(content().string(containsString("value=\"2026-09\"")));
        verify(reportService).report(YearMonth.of(2026, 9));

        when(reportService.report(YearMonth.of(2026, 8))).thenReturn(completedMonth(new BigDecimal("53.33")));
        mockMvc.perform(get("/reports/monthly-occupancy").param("month", "2026-08").with(viewer()))
                .andExpect(content().string(containsString("value=\"2026-08\"")));
        verify(reportService).report(YearMonth.of(2026, 8));
    }

    /** Confirms an invalid month is handled friendly, runs no report and is not a 500. */
    @Test
    void shouldHandleInvalidMonthFriendly() throws Exception {
        for (String invalid : List.of("2026-13", "abc", "2026-9", "20260", "2026-00", "../x", "+12345-01")) {
            mockMvc.perform(get("/reports/monthly-occupancy").param("month", invalid).cookie(language("en")).with(viewer()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Please choose a valid month (yyyy-MM).")));
        }
        verify(reportService, never()).report(any());
    }

    /** Confirms a future month shows the friendly message and no report figures. */
    @Test
    void shouldShowFriendlyMessageForFutureMonth() throws Exception {
        when(reportService.report(any())).thenThrow(
                new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "internal"));

        mockMvc.perform(get("/reports/monthly-occupancy").param("month", "2027-01").cookie(language("en")).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Occupancy cannot be reported for a future month.")))
                .andExpect(content().string(containsString("value=\"2027-01\"")))
                .andExpect(content().string(not(containsString("id=\"occupancy-summary\""))));
    }

    /** Confirms an unsupported (pre-history) month shows the first supported month in both languages. */
    @Test
    void shouldShowFriendlyMessageWhenHistoryIsUnavailable() throws Exception {
        when(reportService.report(any())).thenThrow(
                new ReportPeriodUnavailableException(Reason.HISTORY_UNAVAILABLE, YearMonth.of(2026, 10), "internal"));

        mockMvc.perform(get("/reports/monthly-occupancy").param("month", "2026-09").cookie(language("en")).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Occupancy history is only available from 10/2026.")))
                .andExpect(content().string(not(containsString("id=\"occupancy-summary\""))));
        mockMvc.perform(get("/reports/monthly-occupancy").param("month", "2026-09").cookie(language("vi")).with(viewer()))
                .andExpect(content().string(containsString("Lịch sử công suất phòng chỉ có từ 10/2026.")));
    }

    /** Confirms an integrity failure gives a generic translated message with HTTP 500 and no internals. */
    @Test
    void shouldShowFriendlyMessageOnIntegrityFailure() throws Exception {
        when(reportService.report(any())).thenThrow(new ReportDataIntegrityException("SECRET room " + UUID.randomUUID()));

        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("en")).with(viewer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("cannot be produced")))
                .andExpect(content().string(not(containsString("SECRET"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    /** Confirms English rendering of the summary, the definition note and the Room Type table. */
    @Test
    void shouldRenderEnglish() throws Exception {
        when(reportService.report(any())).thenReturn(completedMonth(new BigDecimal("53.33")));

        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("en")).with(viewer()))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Monthly Occupancy Report")))
                .andExpect(content().string(containsString("Occupied Room Nights")))
                .andExpect(content().string(containsString("Available Room Nights")))
                .andExpect(content().string(containsString("Occupancy Rate")))
                .andExpect(content().string(containsString("Room Type Performance")))
                .andExpect(content().string(containsString("Room Type")))
                .andExpect(content().string(containsString("53.33%")))
                .andExpect(content().string(containsString("sellable inventory")))
                .andExpect(content().string(not(containsString("id=\"data-through\""))));
    }

    /** Confirms Vietnamese rendering. */
    @Test
    void shouldRenderVietnamese() throws Exception {
        when(reportService.report(any())).thenReturn(completedMonth(new BigDecimal("53.33")));

        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("vi")).with(viewer()))
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andExpect(content().string(containsString("Báo cáo công suất phòng tháng")))
                .andExpect(content().string(containsString("Số đêm phòng có khách")))
                .andExpect(content().string(containsString("Số đêm phòng khả dụng")))
                .andExpect(content().string(containsString("Tỷ lệ lấp đầy")))
                .andExpect(content().string(containsString("Hiệu suất theo loại phòng")))
                .andExpect(content().string(containsString("Loại phòng")))
                .andExpect(content().string(not(containsString("Occupied Room Nights"))));
    }

    /** Confirms the current month shows "Data through" the last completed night, in both languages. */
    @Test
    void shouldShowDataThroughForTheCurrentMonth() throws Exception {
        when(reportService.report(any())).thenReturn(currentMonth());

        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("en")).with(viewer()))
                .andExpect(content().string(containsString("id=\"data-through\"")))
                .andExpect(content().string(containsString("Data through 17/09/2026")));
        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("vi")).with(viewer()))
                .andExpect(content().string(containsString("Dữ liệu đến hết 17/09/2026")));
    }

    /** Confirms N/A is shown when the rate is null, and the empty state when no room type is represented. */
    @Test
    void shouldShowNotApplicableForNullRate() throws Exception {
        when(reportService.report(any())).thenReturn(new MonthlyOccupancyReport(
                YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), null, 0, 0, null, List.of()));

        mockMvc.perform(get("/reports/monthly-occupancy").cookie(language("en")).with(viewer()))
                .andExpect(content().string(containsString("N/A")))
                .andExpect(content().string(containsString("No nights have been completed yet in this month.")))
                .andExpect(content().string(containsString("No room inventory was recorded for this period.")));
    }

    /** Enables method security and supplies a fixed hotel Clock (18/09/2026). */
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
