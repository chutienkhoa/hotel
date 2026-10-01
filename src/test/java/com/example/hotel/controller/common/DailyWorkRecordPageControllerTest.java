package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.request.DailyWorkRecordLineForm;
import com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DailyWorkRecordService;
import com.example.hotel.service.common.StaffService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies Daily Work Record MVC authorization, date loading, indexed bulk-save binding, and
 * routing. {@link StaffPageController} is loaded alongside this controller specifically to prove
 * {@code POST /staff/daily-work-record} resolves to this controller's literal path mapping and is
 * never swallowed by {@link StaffPageController}'s {@code POST /staff/{id}} variable mapping,
 * since both controllers are registered simultaneously in the real application.
 */
@WebMvcTest({DailyWorkRecordPageController.class, StaffPageController.class})
@Import({
    DailyWorkRecordPageControllerTest.MethodSecurityTestConfiguration.class,
    DailyWorkRecordPageControllerTest.ClockTestConfiguration.class
})
class DailyWorkRecordPageControllerTest {

    private static final UUID STAFF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_STAFF_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 9, 18);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DailyWorkRecordService dailyWorkRecordService;

    @MockitoBean
    private StaffService staffService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN/MANAGER-equivalent MANAGE_ATTENDANCE access loads the screen for the selected date. */
    @Test
    void shouldLoadSelectedWorkDate() throws Exception {
        when(dailyWorkRecordService.loadLines(WORK_DATE)).thenReturn(List.of(line(STAFF_ID, "STF-000001", "Nguyen Van A")));

        mockMvc.perform(get("/staff/daily-work-record").param("workDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Daily Work Record")))
                .andExpect(content().string(containsString("STF-000001")))
                .andExpect(content().string(containsString("Nguyen Van A")));

        verify(dailyWorkRecordService).loadLines(WORK_DATE);
    }

    /** Confirms a missing date query parameter defaults to the current hotel date. */
    @Test
    void shouldDefaultToCurrentHotelDateWhenNoDateSupplied() throws Exception {
        when(dailyWorkRecordService.loadLines(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record").with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk());

        verify(dailyWorkRecordService).loadLines(WORK_DATE);
    }

    /** Confirms a user without MANAGE_ATTENDANCE is denied, even with MANAGE_STAFF. */
    @Test
    void shouldDenyUserWithoutManageAttendancePermission() throws Exception {
        mockMvc.perform(get("/staff/daily-work-record")
                        .with(user("staff-manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_STAFF"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms an empty active-Staff list shows an empty state without a broken table. */
    @Test
    void shouldRenderEmptyStateWhenNoActiveStaff() throws Exception {
        when(dailyWorkRecordService.loadLines(WORK_DATE)).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record").param("workDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No active Staff found.")));
    }

    /** Confirms an existing record's values and the derived Working Time populate the form inputs. */
    @Test
    void shouldPopulateExistingValuesAndWorkingTime() throws Exception {
        DailyWorkRecordLineForm existingLine = line(STAFF_ID, "STF-000001", "Nguyen Van A");
        existingLine.setStartTime(LocalTime.of(8, 0));
        existingLine.setEndTime(LocalTime.of(17, 0));
        existingLine.setWorkingTime("9h00");
        when(dailyWorkRecordService.loadLines(WORK_DATE)).thenReturn(List.of(existingLine));

        mockMvc.perform(get("/staff/daily-work-record").param("workDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"08:00\"")))
                .andExpect(content().string(containsString("value=\"17:00\"")))
                .andExpect(content().string(containsString("9h00")));
    }

    /** Confirms a bulk save with two Staff lines binds the indexed list correctly and delegates to the service. */
    @Test
    void shouldBindIndexedLinesAndSaveBulk() throws Exception {
        mockMvc.perform(post("/staff/daily-work-record")
                        .param("workDate", "2026-09-18")
                        .param("lines[0].staffId", STAFF_ID.toString())
                        .param("lines[0].staffCode", "STF-000001")
                        .param("lines[0].staffName", "Nguyen Van A")
                        .param("lines[0].startTime", "08:00")
                        .param("lines[0].endTime", "17:00")
                        .param("lines[1].staffId", SECOND_STAFF_ID.toString())
                        .param("lines[1].staffCode", "STF-000002")
                        .param("lines[1].staffName", "Tran Thi B")
                        .param("lines[1].startTime", "07:30")
                        .param("lines[1].endTime", "16:30")
                        .with(user("admin").authorities(manageAttendanceAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/daily-work-record?workDate=2026-09-18"));

        var dateCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        var linesCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(dailyWorkRecordService).saveBulk(dateCaptor.capture(), linesCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(WORK_DATE, dateCaptor.getValue());
        List<DailyWorkRecordLineForm> savedLines = linesCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(2, savedLines.size());
        org.junit.jupiter.api.Assertions.assertEquals(STAFF_ID, savedLines.get(0).getStaffId());
        org.junit.jupiter.api.Assertions.assertEquals(LocalTime.of(8, 0), savedLines.get(0).getStartTime());
        org.junit.jupiter.api.Assertions.assertEquals(LocalTime.of(17, 0), savedLines.get(0).getEndTime());
        org.junit.jupiter.api.Assertions.assertEquals(SECOND_STAFF_ID, savedLines.get(1).getStaffId());
        org.junit.jupiter.api.Assertions.assertEquals(LocalTime.of(7, 30), savedLines.get(1).getStartTime());
    }

    /** Confirms a validation failure redisplays the same form with the submitted values and a safe error. */
    @Test
    void shouldRedisplayFormWithErrorOnValidationFailure() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tran Thi B: Start and End time must both be provided, or both left blank."))
                .when(dailyWorkRecordService)
                .saveBulk(eq(WORK_DATE), any());

        mockMvc.perform(post("/staff/daily-work-record")
                        .param("workDate", "2026-09-18")
                        .param("lines[0].staffId", STAFF_ID.toString())
                        .param("lines[0].staffCode", "STF-000001")
                        .param("lines[0].staffName", "Nguyen Van A")
                        .param("lines[0].startTime", "08:00")
                        .param("lines[0].endTime", "17:00")
                        .param("lines[1].staffId", SECOND_STAFF_ID.toString())
                        .param("lines[1].staffCode", "STF-000002")
                        .param("lines[1].staffName", "Tran Thi B")
                        .param("lines[1].startTime", "07:30")
                        .with(user("admin").authorities(manageAttendanceAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Tran Thi B: Start and End time must both be provided, or both left blank.")))
                .andExpect(content().string(containsString("value=\"08:00\"")));
    }

    /** Confirms Save requires CSRF like every other mutating form. */
    @Test
    void shouldRequireCsrfForSave() throws Exception {
        mockMvc.perform(post("/staff/daily-work-record")
                        .param("workDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms {@code POST /staff/daily-work-record} routes to this controller's literal mapping
     * and is never swallowed by {@link StaffPageController}'s {@code POST /staff/{id}} variable
     * mapping, with both controllers registered simultaneously (the real application topology).
     */
    @Test
    void shouldRouteDailyWorkRecordLiteralPathWithoutAmbiguity() throws Exception {
        mockMvc.perform(post("/staff/daily-work-record")
                        .param("workDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/daily-work-record?workDate=2026-09-18"));

        verify(dailyWorkRecordService).saveBulk(eq(WORK_DATE), any());
        verify(staffService, org.mockito.Mockito.never()).update(any(), any());
    }

    /**
     * Confirms {@code POST /staff/{id}} (a real UUID) still routes to {@link StaffPageController}
     * correctly with both controllers registered together, proving the literal
     * {@code /staff/daily-work-record} mapping did not accidentally shadow the variable one.
     */
    @Test
    void shouldStillRouteStaffUpdateByIdWithBothControllersRegistered() throws Exception {
        UUID staffId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(staffService.update(eq(staffId), any())).thenReturn(new StaffResponse(
                staffId, "STF-000003", "Le", "Van C", null, null, null, WORK_DATE, true, null));

        mockMvc.perform(post("/staff/{id}", staffId)
                        .param("firstName", "Le")
                        .param("lastName", "Van C")
                        .param("startDate", "2026-09-18")
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("PERM_MANAGE_STAFF"),
                                new SimpleGrantedAuthority("PERM_MANAGE_ATTENDANCE")))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));

        verify(staffService).update(eq(staffId), any());
        verify(dailyWorkRecordService, org.mockito.Mockito.never()).saveBulk(any(), any());
    }

    /**
     * Confirms {@code GET /staff/daily-work-record} routes to this controller's literal mapping
     * and is never swallowed by {@link StaffPageController}'s new {@code GET /staff/{id}} variable
     * mapping, with both controllers registered simultaneously (the real application topology).
     */
    @Test
    void shouldRouteDailyWorkRecordGetLiteralPathWithoutAmbiguity() throws Exception {
        when(dailyWorkRecordService.loadLines(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Daily Work Record")));

        verify(staffService, org.mockito.Mockito.never()).findById(any());
    }

    /** Confirms the By Staff screen is reachable with MANAGE_ATTENDANCE, showing By Date/By Staff tabs. */
    @Test
    void shouldAccessByStaffScreenWithManageAttendance() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("By Date")))
                .andExpect(content().string(containsString("By Staff")))
                .andExpect(content().string(containsString("Select a Staff member to search their Work History.")));
    }

    /** Confirms a user without MANAGE_ATTENDANCE is denied the By Staff screen. */
    @Test
    void shouldDenyByStaffScreenWithoutManageAttendancePermission() throws Exception {
        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .with(user("staff-manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_STAFF"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Staff selector lists both active and inactive Staff, so historical search is possible. */
    @Test
    void shouldListActiveAndInactiveStaffInSelector() throws Exception {
        when(staffService.search(any())).thenReturn(List.of(
                new StaffResponse(STAFF_ID, "STF-000001", "Nguyen", "Van A", null, null, null, WORK_DATE, true, null),
                new StaffResponse(
                        SECOND_STAFF_ID, "STF-000002", "Tran", "Thi B", null, null, null, WORK_DATE, false, null)));

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("STF-000001 - Nguyen Van A")))
                .andExpect(content().string(containsString("STF-000002 - Tran Thi B")));
    }

    /** Confirms the default range is the first day of the current hotel month through the current hotel date. */
    @Test
    void shouldDefaultByStaffDateRangeToCurrentHotelMonth() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record/by-staff").param("staffId", STAFF_ID.toString())
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk());

        verify(dailyWorkRecordService).history(STAFF_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 18));
    }

    /** Confirms a search returns the selected Staff member's Work History, newest work date first, with Notes. */
    @Test
    void shouldReturnWorkHistoryForSelectedStaffNewestFirstWithNotes() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());
        when(dailyWorkRecordService.history(STAFF_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 18)))
                .thenReturn(List.of(
                        new DailyWorkRecordHistoryLine(
                                LocalDate.of(2026, 9, 18), LocalTime.of(8, 10), LocalTime.of(17, 15), "9h05", "Late shift"),
                        new DailyWorkRecordHistoryLine(
                                LocalDate.of(2026, 9, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), "9h00", null)));

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .param("staffId", STAFF_ID.toString())
                        .param("fromDate", "2026-09-01")
                        .param("toDate", "2026-09-18")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("9h05")))
                .andExpect(content().string(containsString("Late shift")))
                .andExpect(content().string(containsString("9h00")));
    }

    /** Confirms the selected Staff member and date range are preserved in the form after a search. */
    @Test
    void shouldPreserveSelectedStaffAndDateRangeAfterSearch() throws Exception {
        when(staffService.search(any())).thenReturn(List.of(
                new StaffResponse(STAFF_ID, "STF-000001", "Nguyen", "Van A", null, null, null, WORK_DATE, true, null)));
        when(dailyWorkRecordService.history(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .param("staffId", STAFF_ID.toString())
                        .param("fromDate", "2026-09-05")
                        .param("toDate", "2026-09-10")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"2026-09-05\"")))
                .andExpect(content().string(containsString("value=\"2026-09-10\"")));
    }

    /** Confirms an invalid range shows a safe inline error rather than a generic error page. */
    @Test
    void shouldShowSafeErrorWhenByStaffFromAfterTo() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .param("staffId", STAFF_ID.toString())
                        .param("fromDate", "2026-09-18")
                        .param("toDate", "2026-09-01")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("From date must not be after To date.")));

        verify(dailyWorkRecordService, org.mockito.Mockito.never()).history(any(), any(), any());
    }

    /** Confirms an empty result for the selected Staff/range renders a proper empty state, no synthesized rows. */
    @Test
    void shouldRenderEmptyStateWhenNoWorkHistoryRecordsFound() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());
        when(dailyWorkRecordService.history(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record/by-staff").param("staffId", STAFF_ID.toString())
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No work records found for this period.")));
    }

    /** Confirms switching between By Date and By Staff is possible via the tab links on each screen. */
    @Test
    void shouldExposeTabLinksBetweenByDateAndByStaff() throws Exception {
        when(dailyWorkRecordService.loadLines(any())).thenReturn(List.of());
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/daily-work-record").with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/daily-work-record/by-staff\"")));

        mockMvc.perform(get("/staff/daily-work-record/by-staff")
                        .with(user("admin").authorities(manageAttendanceAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/daily-work-record\"")));
    }

    /** Builds a representative Daily Work Record form line for one Staff member with blank times. */
    private DailyWorkRecordLineForm line(UUID staffId, String staffCode, String staffName) {
        DailyWorkRecordLineForm line = new DailyWorkRecordLineForm();
        line.setStaffId(staffId);
        line.setStaffCode(staffCode);
        line.setStaffName(staffName);
        return line;
    }

    /** Builds the MANAGE_ATTENDANCE authority. */
    private static List<SimpleGrantedAuthority> manageAttendanceAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_ATTENDANCE"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Supplies a fixed Clock so the "default to current hotel date" behavior is deterministic. */
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
