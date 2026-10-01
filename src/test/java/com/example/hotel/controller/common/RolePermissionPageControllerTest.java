package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.dto.common.response.RolePermissionMatrixResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.RolePermissionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Roles &amp; Permissions MVC authorization, CSRF, rendering, form binding and error handling. */
@WebMvcTest(RolePermissionPageController.class)
@Import(RolePermissionPageControllerTest.MethodSecurityTestConfiguration.class)
class RolePermissionPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RolePermissionService rolePermissionService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the page renders the database-driven matrix, locked MANAGE_USER row and friendly labels only. */
    @Test
    void shouldRenderMatrixWithoutRawAuthorityStringsOrDormantPermission() throws Exception {
        when(rolePermissionService.loadMatrix()).thenReturn(matrix());

        mockMvc.perform(get("/roles-permissions").with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Roles &amp; Permissions")))
                .andExpect(content().string(containsString("View Reports")))
                .andExpect(content().string(containsString("Manage Users")))
                .andExpect(content().string(containsString("class=\"roles-matrix-locked\"")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*Manage Users for ADMIN \\(locked\\)\"\\s+disabled\\s+type=\"checkbox\"\\s+checked=\"checked\".*")))
                .andExpect(content().string(not(matchesPattern(
                        "(?s).*Manage Users for MANAGER \\(locked\\)\"\\s+disabled\\s+type=\"checkbox\"\\s+checked=\"checked\".*"))))
                .andExpect(content().string(matchesPattern(
                        "(?s).*name=\"grants\\[MANAGER\\]\"\\s+value=\"VIEW_REPORT\"\\s+type=\"checkbox\"\\s+checked=\"checked\".*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*name=\"grants\\[STAFF\\]\"\\s+value=\"VIEW_REPORT\"\\s+type=\"checkbox\"\\s*/>.*")))
                .andExpect(content().string(not(containsString("value=\"MANAGE_USER\""))))
                .andExpect(content().string(not(containsString("<label"))))
                .andExpect(content().string(not(containsString("for=\"grants"))))
                .andExpect(content().string(containsString("class=\"roles-matrix\"")))
                .andExpect(content().string(not(containsString("DELETE_RESERVATION"))))
                .andExpect(content().string(not(containsString("PERM_"))))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-roles\"")));
    }

    /** Confirms MANAGER- and STAFF-style users are denied every route even with CSRF. */
    @Test
    void shouldDenyUsersWithoutManageUser() throws Exception {
        var manager = user("manager").authorities(
                new SimpleGrantedAuthority("PERM_MANAGE_STAFF"), new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));

        mockMvc.perform(get("/roles-permissions").with(manager)).andExpect(status().isForbidden());
        mockMvc.perform(post("/roles-permissions").with(manager).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(rolePermissionService);
    }

    /** Confirms saving requires a CSRF token. */
    @Test
    void shouldRequireCsrf() throws Exception {
        mockMvc.perform(post("/roles-permissions").with(user("admin").authorities(manageUser())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rolePermissionService);
    }

    /** Confirms multi-value checkbox fields bind to the per-role grants and a save redirects with a success flash. */
    @Test
    void shouldBindGrantsAndRedirectWithSuccess() throws Exception {
        mockMvc.perform(post("/roles-permissions")
                        .param("submittedRoles", "ADMIN", "MANAGER", "STAFF")
                        .param("grants[ADMIN]", "VIEW_REPORT", "VIEW_BOOKING")
                        .param("grants[MANAGER]", "VIEW_BOOKING")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/roles-permissions"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("successMessage", "Role permissions updated successfully."));

        ArgumentCaptor<RolePermissionUpdateRequest> captor = ArgumentCaptor.forClass(RolePermissionUpdateRequest.class);
        verify(rolePermissionService).update(captor.capture());
        assertEquals(List.of("ADMIN", "MANAGER", "STAFF"), captor.getValue().getSubmittedRoles());
        assertEquals(List.of("VIEW_REPORT", "VIEW_BOOKING"), captor.getValue().getGrants().get("ADMIN"));
        assertEquals(List.of("VIEW_BOOKING"), captor.getValue().getGrants().get("MANAGER"));
    }

    /** Confirms a rejected (forged) submission redisplays the matrix with a friendly message and no internals. */
    @Test
    void shouldShowFriendlyErrorWhenSubmissionRejected() throws Exception {
        when(rolePermissionService.loadMatrix()).thenReturn(matrix());
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Manage Users is reserved for the ADMIN role and cannot be granted to other roles."))
                .when(rolePermissionService).update(any());

        mockMvc.perform(post("/roles-permissions")
                        .param("submittedRoles", "ADMIN", "MANAGER", "STAFF")
                        .param("grants[MANAGER]", "MANAGE_USER")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("reserved for the ADMIN role")))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    private RolePermissionMatrixResponse matrix() {
        Map<String, Boolean> viewReport = new LinkedHashMap<>();
        viewReport.put("ADMIN", true);
        viewReport.put("MANAGER", true);
        viewReport.put("STAFF", false);
        Map<String, Boolean> manageUser = new LinkedHashMap<>();
        manageUser.put("ADMIN", true);
        manageUser.put("MANAGER", false);
        manageUser.put("STAFF", false);
        return new RolePermissionMatrixResponse(List.of("ADMIN", "MANAGER", "STAFF"), List.of(
                new RolePermissionMatrixResponse.Group("Dashboard", List.of(
                        new RolePermissionMatrixResponse.Item("VIEW_REPORT", "View Reports", false, viewReport))),
                new RolePermissionMatrixResponse.Group("Administration", List.of(
                        new RolePermissionMatrixResponse.Item("MANAGE_USER", "Manage Users", true, manageUser)))));
    }

    private static List<SimpleGrantedAuthority> manageUser() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_USER"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
