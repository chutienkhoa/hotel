package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DailyWorkRecordService;
import com.example.hotel.service.common.StaffService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end EN/VI proof on the representative Staff flow: shared shell, sidebar, table-adjacent
 * strings, enum labels, confirmation text, JS bridge, validation, and flash/business messages.
 * The runtime default (Vietnamese) is pinned explicitly; existing tests default to English.
 */
@WebMvcTest(StaffPageController.class)
@Import({StaffI18nTest.MethodSecurityTestConfiguration.class, StaffI18nTest.ClockTestConfiguration.class, I18nConfig.class})
@TestPropertySource(properties = "hotel.i18n.default-locale=vi")
class StaffI18nTest {

    private static final UUID STAFF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StaffService staffService;

    @MockitoBean
    private DailyWorkRecordService dailyWorkRecordService;

    @MockitoBean
    private JwtService jwtService;

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin").authorities(new SimpleGrantedAuthority("PERM_MANAGE_STAFF"),
                new SimpleGrantedAuthority("PERM_MANAGE_ATTENDANCE"), new SimpleGrantedAuthority("PERM_MANAGE_USER"));
    }

    private StaffResponse staff(boolean active) {
        return new StaffResponse(STAFF_ID, "STF-000001", "Nguyen", "Van A", "0900000001", "a@example.test",
                "Receptionist", LocalDate.of(2026, 1, 1), active, null);
    }

    /** Confirms the Vietnamese page renders title, filters, headers, enum labels and shared shell in Vietnamese. */
    @Test
    void shouldRenderStaffListInVietnameseByDefault() throws Exception {
        when(staffService.search(any())).thenReturn(List.of(staff(true), staff(false)));

        mockMvc.perform(get("/staff").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andExpect(content().string(containsString("Quản lý nhân viên")))
                .andExpect(content().string(containsString("Thêm nhân viên")))
                .andExpect(content().string(containsString("Tìm nhân viên")))
                .andExpect(content().string(containsString("Mã nhân viên, tên, số điện thoại, email, chức vụ")))
                .andExpect(content().string(containsString(">Đang hoạt động<")))
                .andExpect(content().string(containsString(">Ngừng hoạt động<")))
                // Task33 Batch 1A titles start with the untranslated brand "Sunset Hotel" (layout/base.html), unchanged by locale.
                .andExpect(content().string(containsString("<title>Sunset Hotel | Nhân viên</title>")))
                // shared sidebar / account
                .andExpect(content().string(containsString("Quản trị")))
                .andExpect(content().string(containsString("Đăng xuất")))
                .andExpect(content().string(containsString("Vai trò &amp; Quyền")))
                // confirmation dialog + page-provided confirmation text (with message arguments)
                .andExpect(content().string(containsString("Xác nhận thao tác")))
                .andExpect(content().string(containsString("Bạn có chắc chắn muốn tiếp tục không?")))
                .andExpect(content().string(containsString("data-confirm-message=\"Ngừng hoạt động STF-000001?\"")))
                // JavaScript bridge
                .andExpect(content().string(containsString("name=\"pms-i18n:js.datePicker.today\" content=\"Hôm nay\"")))
                .andExpect(content().string(not(containsString("Add Staff"))));
    }

    /** Confirms the same page renders English when the cookie selects English, keeping internal values unchanged. */
    @Test
    void shouldRenderStaffListInEnglishWithCookie() throws Exception {
        when(staffService.search(any())).thenReturn(List.of(staff(true), staff(false)));

        mockMvc.perform(get("/staff").cookie(language("en")).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Add Staff")))
                .andExpect(content().string(containsString("Administration")))
                .andExpect(content().string(containsString("Are you sure you want to continue?")))
                .andExpect(content().string(containsString(">Active<")))
                .andExpect(content().string(containsString("value=\"ACTIVE\"")))
                .andExpect(content().string(containsString("data-confirm-message=\"Deactivate STF-000001?\"")))
                .andExpect(content().string(containsString("name=\"pms-i18n:js.datePicker.today\" content=\"Today\"")))
                .andExpect(content().string(not(containsString("Thêm nhân viên"))));
    }

    /** Confirms the switcher keeps the current URL and query (except lang) and never leaks request-body data. */
    @Test
    void shouldKeepQueryWhenBuildingLanguageLinks() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff?query=an&status=ACTIVE&lang=en").with(admin()))
                .andExpect(content().string(containsString("href=\"/staff?query=an&amp;status=ACTIVE&amp;lang=vi\"")))
                .andExpect(content().string(containsString("href=\"/staff?query=an&amp;status=ACTIVE&amp;lang=en\"")));
    }

    /** Confirms the language links of a failed CREATE point at the GET form, never at the POST target. */
    @Test
    void shouldPointLanguageLinksAtCreateFormAfterFailedCreate() throws Exception {
        for (String language : List.of("vi", "en")) {
            mockMvc.perform(post("/staff").cookie(language(language)).with(admin()).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("href=\"/staff/new?lang=vi\"")))
                    .andExpect(content().string(containsString("href=\"/staff/new?lang=en\"")))
                    .andExpect(content().string(not(containsString("href=\"/staff?lang="))));
        }
    }

    /** Confirms a business-rule failure on create keeps the same GET form target. */
    @Test
    void shouldPointLanguageLinksAtCreateFormAfterBusinessErrorOnCreate() throws Exception {
        when(staffService.create(any())).thenThrow(new LocalizedResponseStatusException(
                HttpStatus.CONFLICT, "staff.error.codeExists", "Staff Code already exists"));

        mockMvc.perform(post("/staff").param("firstName", "A").param("lastName", "B").param("startDate", "2026-01-01")
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/new?lang=en\"")));
    }

    /** Confirms the language links of a failed EDIT point at the GET edit form for that Staff member. */
    @Test
    void shouldPointLanguageLinksAtEditFormAfterFailedEdit() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(staff(true));

        mockMvc.perform(post("/staff/{id}", STAFF_ID).cookie(language("vi")).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/" + STAFF_ID + "/edit?lang=en\"")))
                .andExpect(content().string(containsString("href=\"/staff/" + STAFF_ID + "/edit?lang=vi\"")))
                .andExpect(content().string(not(containsString("href=\"/staff/" + STAFF_ID + "?lang="))));
    }

    /** Confirms a business-rule failure on edit keeps the GET edit-form target. */
    @Test
    void shouldPointLanguageLinksAtEditFormAfterBusinessErrorOnEdit() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(staff(true));
        when(staffService.update(any(), any())).thenThrow(new LocalizedResponseStatusException(
                HttpStatus.CONFLICT, "staff.error.codeExists", "Staff Code already exists"));

        mockMvc.perform(post("/staff/{id}", STAFF_ID).param("firstName", "A").param("lastName", "B")
                        .param("startDate", "2026-01-01").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/" + STAFF_ID + "/edit?lang=en\"")));
    }

    /** Confirms MVC validation messages are localized from the bundles. */
    @Test
    void shouldLocalizeValidationMessages() throws Exception {
        mockMvc.perform(post("/staff").cookie(language("vi")).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vui lòng nhập tên.")))
                .andExpect(content().string(containsString("Vui lòng nhập họ.")))
                .andExpect(content().string(containsString("Vui lòng chọn ngày bắt đầu.")))
                .andExpect(content().string(containsString("Vui lòng sửa các trường được đánh dấu.")));
        mockMvc.perform(post("/staff").cookie(language("en")).with(admin()).with(csrf()))
                .andExpect(content().string(containsString("First name is required.")))
                .andExpect(content().string(containsString("Start date is required.")));
        mockMvc.perform(post("/staff").cookie(language("vi")).param("firstName", "A".repeat(101)).param("lastName", "B")
                        .param("startDate", "2026-01-01").param("email", "nope").with(admin()).with(csrf()))
                .andExpect(content().string(containsString("Tối đa 100 ký tự.")))
                .andExpect(content().string(containsString("Email không hợp lệ.")));
    }

    /** Confirms a key-carrying business error and an argument-based flash message are translated per locale. */
    @Test
    void shouldLocalizeBusinessErrorAndSuccessFlashMessages() throws Exception {
        doThrow(new LocalizedResponseStatusException(HttpStatus.CONFLICT, "staff.error.cannotDeactivate",
                "Staff cannot deactivate from its current state"))
                .when(staffService).deactivate(STAFF_ID);
        when(staffService.reactivate(STAFF_ID)).thenReturn(staff(true));

        mockMvc.perform(post("/staff/{id}/deactivate", STAFF_ID).cookie(language("vi")).with(admin()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "Không thể ngừng hoạt động nhân viên ở trạng thái hiện tại"));
        mockMvc.perform(post("/staff/{id}/deactivate", STAFF_ID).cookie(language("en")).with(admin()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "Staff cannot deactivate from its current state"));
        mockMvc.perform(post("/staff/{id}/reactivate", STAFF_ID).cookie(language("vi")).with(admin()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "Đã kích hoạt lại nhân viên STF-000001."));
        mockMvc.perform(post("/staff/{id}/reactivate", STAFF_ID).cookie(language("en")).with(admin()).with(csrf()))
                .andExpect(flash().attribute("successMessage", "Staff member STF-000001 reactivated successfully."));
    }

    /** Confirms dates keep the dd/MM/yyyy display in both languages and the form keeps the ISO submission value. */
    @Test
    void shouldKeepDateDisplayAndSubmissionFormatsIndependentOfLanguage() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(staff(true));
        when(dailyWorkRecordService.history(any(), any(), any())).thenReturn(List.of());

        for (String language : List.of("vi", "en")) {
            mockMvc.perform(get("/staff/{id}", STAFF_ID).cookie(language(language)).with(admin()))
                    .andExpect(content().string(containsString("01/01/2026")));
            mockMvc.perform(get("/staff/{id}/edit", STAFF_ID).cookie(language(language)).with(admin()))
                    .andExpect(content().string(containsString("value=\"2026-01-01\"")));
        }
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Supplies a fixed Clock. */
    @TestConfiguration
    static class ClockTestConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(
                    LocalDate.of(2026, 9, 18).atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
