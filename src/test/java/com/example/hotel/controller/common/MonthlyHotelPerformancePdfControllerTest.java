package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.MonthlyHotelPerformancePdfRenderer;
import com.example.hotel.service.common.MonthlyHotelPerformanceReportService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
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

/** Verifies the PDF download endpoint: authorization, response headers, month handling, locale and rejection behavior. */
@WebMvcTest(MonthlyHotelPerformancePdfController.class)
@Import({MonthlyHotelPerformancePdfControllerTest.TestConfig.class, I18nConfig.class})
class MonthlyHotelPerformancePdfControllerTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyHotelPerformanceReportService reportService;

    @MockitoBean
    private MonthlyHotelPerformancePdfRenderer renderer;

    @MockitoBean
    private JwtService jwtService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    private MonthlyHotelPerformanceReport stubReport(YearMonth month) {
        MonthlyHotelPerformanceReport report = new MonthlyHotelPerformanceReport(
                month, LocalDate.of(2026, 9, 18), null, null, null, List.of(), 0, List.of(), List.of());
        when(reportService.build(month)).thenReturn(report);
        when(renderer.render(eq(report), any(Locale.class))).thenReturn(PDF);
        return report;
    }

    /** Confirms VIEW_REPORT gets an attachment PDF with the deterministic filename and no-store caching. */
    @Test
    void shouldDownloadPdfWithHeaders() throws Exception {
        stubReport(YearMonth.of(2026, 9));

        mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", "2026-09").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"hotel-performance-2026-09.pdf\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().longValue("Content-Length", PDF.length))
                .andExpect(content().bytes(PDF));
    }

    /** Confirms an omitted month uses the current hotel month from the Clock. */
    @Test
    void shouldDefaultToCurrentHotelMonth() throws Exception {
        stubReport(YearMonth.of(2026, 9));

        mockMvc.perform(get("/reports/monthly-performance.pdf").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("hotel-performance-2026-09.pdf")));

        verify(reportService).build(YearMonth.of(2026, 9));
    }

    /** Confirms users without VIEW_REPORT are forbidden, anonymous users rejected, and nothing is generated. */
    @Test
    void shouldDenyWithoutPermissionAndRejectAnonymous() throws Exception {
        mockMvc.perform(get("/reports/monthly-performance.pdf").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/monthly-performance.pdf")).andExpect(status().is4xxClientError());

        verify(reportService, never()).build(any());
        verify(renderer, never()).render(any(), any());
    }

    /** Confirms the PDF follows the current PMS UI locale (cookie), with no separate language parameter. */
    @Test
    void shouldRenderInTheCurrentUiLocale() throws Exception {
        MonthlyHotelPerformanceReport report = stubReport(YearMonth.of(2026, 9));

        mockMvc.perform(get("/reports/monthly-performance.pdf").cookie(new Cookie("pms-lang", "vi")).with(viewer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reports/monthly-performance.pdf").cookie(new Cookie("pms-lang", "en")).with(viewer()))
                .andExpect(status().isOk());

        verify(renderer).render(report, Locale.forLanguageTag("vi"));
        verify(renderer).render(report, Locale.forLanguageTag("en"));
    }

    /** Confirms a malformed month redirects to Reports with a flash message and generates nothing. */
    @Test
    void shouldRedirectOnInvalidMonth() throws Exception {
        for (String invalid : List.of("2026-13", "abc", "2026-9", "20260", "+12345-01")) {
            mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", invalid).with(viewer()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reports"))
                    .andExpect(flash().attribute("errorMessage", "Please choose a valid month (yyyy-MM)."));
        }
        verify(reportService, never()).build(any());
    }

    /** Confirms a future month is rejected with a redirect, not a partial PDF. */
    @Test
    void shouldRedirectOnFutureMonth() throws Exception {
        when(reportService.build(any())).thenThrow(new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "future"));

        mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", "2027-01").with(viewer()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports"))
                .andExpect(flash().attribute("errorMessage", "Occupancy cannot be reported for a future month."));
        verify(renderer, never()).render(any(), any());
    }

    /** Confirms a month before supported occupancy history is rejected, in both languages. */
    @Test
    void shouldRedirectWhenOccupancyHistoryIsUnavailable() throws Exception {
        when(reportService.build(any())).thenThrow(
                new ReportPeriodUnavailableException(Reason.HISTORY_UNAVAILABLE, YearMonth.of(2026, 10), "before history"));

        mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", "2026-09").cookie(new Cookie("pms-lang", "en")).with(viewer()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "Occupancy history is only available from 10/2026. Please choose a later month."));
        mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", "2026-09").cookie(new Cookie("pms-lang", "vi")).with(viewer()))
                .andExpect(flash().attribute("errorMessage", "Lịch sử công suất phòng chỉ có từ 10/2026. Vui lòng chọn tháng sau đó."));
        verify(renderer, never()).render(any(), any());
    }

    /** Confirms an integrity failure redirects with a generic message and exposes no internal detail. */
    @Test
    void shouldRedirectOnIntegrityFailureWithoutDetail() throws Exception {
        when(reportService.build(any())).thenThrow(new ReportDataIntegrityException("SECRET room 123"));

        mockMvc.perform(get("/reports/monthly-performance.pdf").param("month", "2026-09").with(viewer()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reports"))
                .andExpect(flash().attribute("errorMessage", not(containsString("SECRET"))))
                .andExpect(flash().attribute("errorMessage", containsString("cannot be produced")));
        verify(renderer, never()).render(any(), any());
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
