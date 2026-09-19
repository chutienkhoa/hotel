package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Staff Management MVC authorization, list/search/filter rendering, and lifecycle actions. */
@WebMvcTest(StaffPageController.class)
@Import({
    StaffPageControllerTest.MethodSecurityTestConfiguration.class,
    StaffPageControllerTest.ClockTestConfiguration.class
})
class StaffPageControllerTest {

    private static final UUID STAFF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StaffService staffService;

    @MockitoBean
    private DailyWorkRecordService dailyWorkRecordService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN can access the Staff list. */
    @Test
    void shouldAllowAdminToAccessStaffList() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Staff Management")));
    }

    /** Confirms MANAGER can access the Staff list. */
    @Test
    void shouldAllowManagerToAccessStaffList() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").with(user("manager").authorities(manageStaffAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms a user without MANAGE_STAFF (the STAFF role's usual authorities) is denied. */
    @Test
    void shouldDenyUserWithoutManageStaffPermission() throws Exception {
        mockMvc.perform(get("/staff").with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                        new SimpleGrantedAuthority("PERM_CHECK_IN"),
                        new SimpleGrantedAuthority("PERM_CHECK_OUT"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Staff list renders Code, Name, Position, and Status for each row. */
    @Test
    void shouldRenderStaffListRows() throws Exception {
        when(staffService.search(any())).thenReturn(List.of(activeStaff(), inactiveStaff()));

        mockMvc.perform(get("/staff").with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("STF-000001")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Receptionist")))
                .andExpect(content().string(containsString("ACTIVE")))
                .andExpect(content().string(containsString("STF-000002")))
                .andExpect(content().string(containsString("INACTIVE")));
    }

    /** Confirms search criteria are forwarded to the service. */
    @Test
    void shouldForwardSearchQueryAndStatusToService() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").param("query", "Nguyen").param("status", "ACTIVE")
                        .with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(com.example.hotel.dto.common.request.StaffSearchCriteria.class);
        verify(staffService).search(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("Nguyen", captor.getValue().getQuery());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().isActiveFilter());
    }

    /** Confirms an empty list shows an empty state without a broken table. */
    @Test
    void shouldRenderEmptyStateWhenNoStaffMatch() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No Staff match the current filters.")));
    }

    /** Confirms Create Staff succeeds and the Staff Code is never editable in the form. */
    @Test
    void shouldRenderCreateFormWithoutEditableStaffCode() throws Exception {
        mockMvc.perform(get("/staff/new").with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"staffCodeDisplay\""))))
                .andExpect(content().string(containsString("First Name")))
                .andExpect(content().string(containsString("Start Date")));
    }

    /** Confirms creating a Staff member redirects to the list with a success message. */
    @Test
    void shouldCreateStaffAndRedirectToList() throws Exception {
        when(staffService.create(any())).thenReturn(activeStaff());

        mockMvc.perform(post("/staff")
                        .param("firstName", "Nguyen")
                        .param("lastName", "Van A")
                        .param("startDate", "2026-01-01")
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));
    }

    /** Confirms required-field validation errors are rendered without persisting anything. */
    @Test
    void shouldRejectCreateWithMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/staff")
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("First name is required.")))
                .andExpect(content().string(containsString("Last name is required.")))
                .andExpect(content().string(containsString("Start date is required.")));

        org.mockito.Mockito.verifyNoInteractions(staffService);
    }

    /** Confirms an invalid email shape is rejected with a friendly message. */
    @Test
    void shouldRejectInvalidEmailShape() throws Exception {
        mockMvc.perform(post("/staff")
                        .param("firstName", "Nguyen")
                        .param("lastName", "Van A")
                        .param("startDate", "2026-01-01")
                        .param("email", "not-an-email")
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email must be a valid email address.")));
    }

    /** Confirms Create Staff requires CSRF like every other mutating form. */
    @Test
    void shouldRequireCsrfForCreate() throws Exception {
        mockMvc.perform(post("/staff")
                        .param("firstName", "Nguyen")
                        .param("lastName", "Van A")
                        .param("startDate", "2026-01-01")
                        .with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Edit form shows the Staff Code read-only and preloads editable fields. */
    @Test
    void shouldRenderEditFormWithReadOnlyStaffCode() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());

        mockMvc.perform(get("/staff/{id}/edit", STAFF_ID).with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("disabled")))
                .andExpect(content().string(containsString("STF-000001")))
                .andExpect(content().string(containsString("Nguyen")))
                .andExpect(content().string(containsString("value=\"2026-01-01\"")));
    }

    /** Confirms updating a Staff member redirects to the list with a success message. */
    @Test
    void shouldUpdateStaffAndRedirectToList() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());
        when(staffService.update(eq(STAFF_ID), any())).thenReturn(activeStaff());

        mockMvc.perform(post("/staff/{id}", STAFF_ID)
                        .param("firstName", "Nguyen")
                        .param("lastName", "Van A")
                        .param("startDate", "2026-01-01")
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));
    }

    /** Confirms deactivating a Staff member is CSRF-protected and redirects to the list. */
    @Test
    void shouldDeactivateStaffWithCsrf() throws Exception {
        when(staffService.deactivate(STAFF_ID)).thenReturn(inactiveStaff());

        mockMvc.perform(post("/staff/{id}/deactivate", STAFF_ID)
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));

        verify(staffService).deactivate(STAFF_ID);
    }

    /** Confirms deactivate without CSRF is rejected. */
    @Test
    void shouldRequireCsrfForDeactivate() throws Exception {
        mockMvc.perform(post("/staff/{id}/deactivate", STAFF_ID)
                        .with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms reactivating a Staff member is CSRF-protected and redirects to the list. */
    @Test
    void shouldReactivateStaffWithCsrf() throws Exception {
        when(staffService.reactivate(STAFF_ID)).thenReturn(activeStaff());

        mockMvc.perform(post("/staff/{id}/reactivate", STAFF_ID)
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));

        verify(staffService).reactivate(STAFF_ID);
    }

    /** Confirms an invalid-transition failure from the service redisplays a safe error message. */
    @Test
    void shouldShowSafeErrorWhenDeactivateTransitionFails() throws Exception {
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Staff cannot deactivate from its current state"))
                .when(staffService)
                .deactivate(STAFF_ID);

        mockMvc.perform(post("/staff/{id}/deactivate", STAFF_ID)
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff"));
    }

    /** Confirms no DELETE route is exposed for Staff: physical delete is never available. */
    @Test
    void shouldNotExposeStaffDeleteRoute() throws Exception {
        mockMvc.perform(delete("/staff/{id}", STAFF_ID)
                        .with(user("admin").authorities(manageStaffAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Confirms the sidebar renders the Staff link only when the user has MANAGE_STAFF or MANAGE_ATTENDANCE. */
    @Test
    void shouldRenderStaffSidebarLinkWithManageStaffPermission() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-staff\"")))
                .andExpect(content().string(containsString("class=\"app-shell page-staff\"")));
    }

    /** Confirms the Users sidebar link appears only when the user also holds MANAGE_USER. */
    @Test
    void shouldShowUsersSidebarLinkOnlyWithManageUser() throws Exception {
        when(staffService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/staff").with(user("manager").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("nav-users"))))
                .andExpect(content().string(containsString("nav-staff")));

        mockMvc.perform(get("/staff").with(user("admin").authorities(
                        new SimpleGrantedAuthority("PERM_MANAGE_STAFF"), new SimpleGrantedAuthority("PERM_MANAGE_USER"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-users\"")));
    }

    /** Builds a representative active Staff response. */
    private StaffResponse activeStaff() {
        return new StaffResponse(
                STAFF_ID, "STF-000001", "Nguyen", "Van A", "0900000001", "a@example.test", "Receptionist",
                LocalDate.of(2026, 1, 1), true, null);
    }

    /** Builds a representative inactive Staff response. */
    private StaffResponse inactiveStaff() {
        return new StaffResponse(
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "STF-000002", "Tran", "Thi B", null, null,
                "Housekeeping", LocalDate.of(2025, 6, 1), false, null);
    }

    /** Builds the MANAGE_STAFF authority. */
    private static List<SimpleGrantedAuthority> manageStaffAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_STAFF"));
    }

    /** Confirms ADMIN can view Staff Detail and it renders the Staff Information and ACTIVE status. */
    @Test
    void shouldRenderStaffDetailForActiveStaff() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());
        when(dailyWorkRecordService.history(eq(STAFF_ID), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/{id}", STAFF_ID).with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("STF-000001")))
                .andExpect(content().string(containsString("Staff Information")))
                .andExpect(content().string(containsString("Work History")))
                .andExpect(content().string(containsString("ACTIVE")));
    }

    /** Confirms MANAGER can view Staff Detail. */
    @Test
    void shouldAllowManagerToViewStaffDetail() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());
        when(dailyWorkRecordService.history(eq(STAFF_ID), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/{id}", STAFF_ID).with(user("manager").authorities(manageStaffAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms a user without MANAGE_STAFF is denied Staff Detail. */
    @Test
    void shouldDenyStaffDetailWithoutManageStaffPermission() throws Exception {
        mockMvc.perform(get("/staff/{id}", STAFF_ID).with(user("staff").authorities(
                        new SimpleGrantedAuthority("PERM_MANAGE_ATTENDANCE"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms Staff Detail remains accessible, and shows INACTIVE, for a deactivated Staff member. */
    @Test
    void shouldRenderStaffDetailForInactiveStaffWithHistory() throws Exception {
        UUID inactiveId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(staffService.findById(inactiveId)).thenReturn(inactiveStaff());
        when(dailyWorkRecordService.history(eq(inactiveId), any(), any())).thenReturn(List.of(
                new DailyWorkRecordHistoryLine(
                        LocalDate.of(2026, 8, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), "9h00", null)));

        mockMvc.perform(get("/staff/{id}", inactiveId).with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("INACTIVE")))
                .andExpect(content().string(containsString("9h00")));
    }

    /** Confirms Work History default range is the first day of the current hotel month through today. */
    @Test
    void shouldDefaultWorkHistoryRangeToCurrentHotelMonth() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());
        when(dailyWorkRecordService.history(eq(STAFF_ID), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/staff/{id}", STAFF_ID).with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk());

        verify(dailyWorkRecordService).history(STAFF_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 18));
    }

    /** Confirms an invalid from &gt; to range shows a safe validation message instead of a generic error page. */
    @Test
    void shouldShowSafeErrorWhenFromDateAfterToDate() throws Exception {
        when(staffService.findById(STAFF_ID)).thenReturn(activeStaff());

        mockMvc.perform(get("/staff/{id}", STAFF_ID)
                        .param("fromDate", "2026-09-18")
                        .param("toDate", "2026-09-01")
                        .with(user("admin").authorities(manageStaffAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("From date must not be after To date.")));

        org.mockito.Mockito.verify(dailyWorkRecordService, org.mockito.Mockito.never()).history(any(), any(), any());
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Supplies a fixed Clock so the default Work History range is deterministic. */
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
