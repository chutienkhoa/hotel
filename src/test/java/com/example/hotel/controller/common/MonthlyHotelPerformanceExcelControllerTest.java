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
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.MonthlyHotelPerformanceExcelRenderer;
import com.example.hotel.service.common.MonthlyHotelPerformanceExcelService;
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

/** Verifies the Excel download endpoint: authorization, headers, month handling, locale and rejection behavior. */
@WebMvcTest(MonthlyHotelPerformanceExcelController.class)
@Import({MonthlyHotelPerformanceExcelControllerTest.TestConfig.class, I18nConfig.class})
class MonthlyHotelPerformanceExcelControllerTest {

    private static final byte[] XLSX = {'P', 'K', 3, 4};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonthlyHotelPerformanceExcelService service;

    @MockitoBean
    private MonthlyHotelPerformanceExcelRenderer renderer;

    @MockitoBean
    private JwtService jwtService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    private MonthlyHotelPerformanceExcelData stub(YearMonth month) {
        MonthlyHotelPerformanceExcelData data = new MonthlyHotelPerformanceExcelData(null, List.of(), List.of(), List.of());
        when(service.build(month)).thenReturn(data);
        when(renderer.render(eq(data), any(Locale.class))).thenReturn(XLSX);
        return data;
    }

    /** Confirms VIEW_REPORT gets an attachment with the XLSX content type, deterministic filename and no-store. */
    @Test
    void shouldDownloadWorkbookWithHeaders() throws Exception {
        stub(YearMonth.of(2026, 9));

        mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", "2026-09").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"hotel-performance-2026-09.xlsx\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().longValue("Content-Length", XLSX.length))
                .andExpect(content().bytes(XLSX));
    }

    /** Confirms an omitted month uses the current hotel month and an explicit month is honored. */
    @Test
    void shouldUseDefaultAndExplicitMonth() throws Exception {
        stub(YearMonth.of(2026, 9));
        stub(YearMonth.of(2026, 8));

        mockMvc.perform(get("/reports/monthly-performance.xlsx").with(viewer()))
                .andExpect(header().string("Content-Disposition", containsString("hotel-performance-2026-09.xlsx")));
        mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", "2026-08").with(viewer()))
                .andExpect(header().string("Content-Disposition", containsString("hotel-performance-2026-08.xlsx")));

        verify(service).build(YearMonth.of(2026, 9));
        verify(service).build(YearMonth.of(2026, 8));
    }

    /** Confirms users without VIEW_REPORT are forbidden, anonymous users rejected, and nothing is generated. */
    @Test
    void shouldDenyWithoutPermissionAndRejectAnonymous() throws Exception {
        mockMvc.perform(get("/reports/monthly-performance.xlsx").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/monthly-performance.xlsx")).andExpect(status().is4xxClientError());

        verify(service, never()).build(any());
        verify(renderer, never()).render(any(), any());
    }

    /** Confirms the workbook follows the current PMS UI locale (cookie), with no language parameter. */
    @Test
    void shouldRenderInTheCurrentUiLocale() throws Exception {
        MonthlyHotelPerformanceExcelData data = stub(YearMonth.of(2026, 9));

        mockMvc.perform(get("/reports/monthly-performance.xlsx").cookie(new Cookie("pms-lang", "vi")).with(viewer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reports/monthly-performance.xlsx").cookie(new Cookie("pms-lang", "en")).with(viewer()))
                .andExpect(status().isOk());

        verify(renderer).render(data, Locale.forLanguageTag("vi"));
        verify(renderer).render(data, Locale.forLanguageTag("en"));
    }

    /** Confirms a malformed month redirects to Reports with a flash message and generates nothing. */
    @Test
    void shouldRedirectOnInvalidMonth() throws Exception {
        for (String invalid : List.of("2026-13", "abc", "2026-9", "20260", "+12345-01")) {
            mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", invalid).with(viewer()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/reports"))
                    .andExpect(flash().attribute("errorMessage", "Please choose a valid month (yyyy-MM)."));
        }
        verify(service, never()).build(any());
    }

    /** Confirms future and unsupported-history months are rejected with localized messages and no workbook. */
    @Test
    void shouldRedirectOnFutureAndUnsupportedMonths() throws Exception {
        when(service.build(YearMonth.of(2027, 1))).thenThrow(new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "x"));
        when(service.build(YearMonth.of(2026, 3))).thenThrow(
                new ReportPeriodUnavailableException(Reason.HISTORY_UNAVAILABLE, YearMonth.of(2026, 10), "y"));

        mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", "2027-01").with(viewer()))
                .andExpect(redirectedUrl("/reports"))
                .andExpect(flash().attribute("errorMessage", "Occupancy cannot be reported for a future month."));
        mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", "2026-03").cookie(new Cookie("pms-lang", "vi")).with(viewer()))
                .andExpect(flash().attribute("errorMessage", "Lịch sử công suất phòng chỉ có từ 10/2026. Vui lòng chọn tháng sau đó."));
        verify(renderer, never()).render(any(), any());
    }

    /** Confirms an integrity failure redirects with a generic message and exposes no internal detail. */
    @Test
    void shouldRedirectOnIntegrityFailureWithoutDetail() throws Exception {
        when(service.build(any())).thenThrow(new ReportDataIntegrityException("SECRET room 123"));

        mockMvc.perform(get("/reports/monthly-performance.xlsx").param("month", "2026-09").with(viewer()))
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
