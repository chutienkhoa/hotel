package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.request.UserCreateRequest;
import com.example.hotel.dto.common.request.UserSearchCriteria;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.dto.common.response.UserResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.security.SessionUserPrincipal;
import com.example.hotel.service.common.UserService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Verifies User Management MVC authorization, CSRF, rendering, and self-management safeguards. */
@WebMvcTest(UserPageController.class)
@Import(UserPageControllerTest.MethodSecurityTestConfiguration.class)
class UserPageControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELF_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms an administrator with MANAGE_USER sees the list, linked Staff, role, and status. */
    @Test
    void shouldListUsersForAdmin() throws Exception {
        when(userService.search(any())).thenReturn(List.of(
                account(USER_ID, "an.le", "STF-000003", "STAFF", true),
                account(SELF_ID, "admin", null, "ADMIN", false)));

        mockMvc.perform(get("/users").with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("an.le")))
                .andExpect(content().string(containsString("STF-000003 - An Le")))
                .andExpect(content().string(containsString("ADMIN")))
                .andExpect(content().string(containsString("INACTIVE")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-users\"")));
    }

    /** Confirms MANAGER/STAFF-style users without MANAGE_USER are denied every read route. */
    @Test
    void shouldDenyUsersWithoutManageUser() throws Exception {
        var manager = user("manager").authorities(
                new SimpleGrantedAuthority("PERM_MANAGE_STAFF"), new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));

        mockMvc.perform(get("/users").with(manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/new").with(manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/{id}", USER_ID).with(manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/{id}/edit", USER_ID).with(manager)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/{id}/reset-password", USER_ID).with(manager)).andExpect(status().isForbidden());
    }

    /** Confirms every mutation route is denied without MANAGE_USER even with a valid CSRF token. */
    @Test
    void shouldDenyMutationsWithoutManageUser() throws Exception {
        var manager = user("manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_STAFF"));

        for (String path : List.of("/users", "/users/" + USER_ID, "/users/" + USER_ID + "/reset-password",
                "/users/" + USER_ID + "/activate", "/users/" + USER_ID + "/deactivate")) {
            mockMvc.perform(post(path).with(manager).with(csrf())).andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    /** Confirms every mutation route rejects a request without a CSRF token. */
    @Test
    void shouldRequireCsrfForEveryMutation() throws Exception {
        for (String path : List.of("/users", "/users/" + USER_ID, "/users/" + USER_ID + "/reset-password",
                "/users/" + USER_ID + "/activate", "/users/" + USER_ID + "/deactivate")) {
            mockMvc.perform(post(path).with(user("admin").authorities(manageUser())))
                    .andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    /** Confirms search and status filters are forwarded to the service. */
    @Test
    void shouldForwardSearchAndStatus() throws Exception {
        when(userService.search(any())).thenReturn(List.of());

        mockMvc.perform(get("/users").param("query", "an").param("status", "ACTIVE")
                        .with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No users match the current filters.")));

        ArgumentCaptor<UserSearchCriteria> captor = ArgumentCaptor.forClass(UserSearchCriteria.class);
        verify(userService).search(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("an", captor.getValue().getQuery());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().isActiveFilter());
    }

    /** Confirms the create form has Staff, username, password, confirmation, role, and no email field. */
    @Test
    void shouldRenderCreateForm() throws Exception {
        when(userService.linkableStaff(null)).thenReturn(List.of(staffOption()));

        mockMvc.perform(get("/users/new").with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("STF-000003 - An Le")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"confirmPassword\"")))
                .andExpect(content().string(containsString("id=\"role\"")))
                .andExpect(content().string(not(containsString("email"))));
    }

    /** Confirms creating a user redirects to the list and passes the submitted fields to the service. */
    @Test
    void shouldCreateUserAndRedirect() throws Exception {
        when(userService.create(any())).thenReturn(account(USER_ID, "an.le", null, "STAFF", true));

        mockMvc.perform(post("/users")
                        .param("username", "An.Le").param("password", "password1")
                        .param("confirmPassword", "password1").param("role", "STAFF")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users"));

        ArgumentCaptor<UserCreateRequest> captor = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(userService).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("An.Le", captor.getValue().username());
    }

    /** Confirms a rejected create redisplays a friendly error, keeps values, and never echoes passwords. */
    @Test
    void shouldRedisplayCreateFormWithoutPasswordOnFailure() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is already taken."))
                .when(userService).create(any());

        mockMvc.perform(post("/users")
                        .param("username", "an.le").param("password", "SecretPass99")
                        .param("confirmPassword", "SecretPass99").param("role", "MANAGER")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Username is already taken.")))
                .andExpect(content().string(containsString("value=\"an.le\"")))
                .andExpect(content().string(not(containsString("SecretPass99"))));
    }

    /** Confirms the edit form shows the username read-only and offers no username or password inputs. */
    @Test
    void shouldRenderEditFormWithReadOnlyUsername() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", "STF-000003", "STAFF", true));
        when(userService.linkableStaff(USER_ID)).thenReturn(List.of(staffOption()));

        mockMvc.perform(get("/users/{id}/edit", USER_ID).with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"usernameDisplay\"")))
                .andExpect(content().string(not(containsString("name=\"username\""))))
                .andExpect(content().string(not(containsString("name=\"password\""))));
    }

    /** Confirms the edit form of the administrator's own account fixes the role instead of offering a selector. */
    @Test
    void shouldNotOfferRoleSelectorOnOwnAccount() throws Exception {
        when(userService.findById(SELF_ID)).thenReturn(account(SELF_ID, "admin", null, "ADMIN", true));
        when(userService.linkableStaff(SELF_ID)).thenReturn(List.of());

        mockMvc.perform(get("/users/{id}/edit", SELF_ID).with(authentication(self())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"roleDisplay\"")))
                .andExpect(content().string(not(containsString("id=\"role\""))));
    }

    /** Confirms updating redirects to the detail page. */
    @Test
    void shouldUpdateUserAndRedirectToDetail() throws Exception {
        when(userService.update(eq(USER_ID), any())).thenReturn(account(USER_ID, "an.le", null, "MANAGER", true));

        mockMvc.perform(post("/users/{id}", USER_ID).param("role", "MANAGER")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + USER_ID));
    }

    /** Confirms a safety-rule conflict on edit is shown as a friendly message. */
    @Test
    void shouldShowFriendlyErrorForRejectedRoleChange() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "ADMIN", true));
        when(userService.linkableStaff(USER_ID)).thenReturn(List.of());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "At least one active ADMIN account must remain."))
                .when(userService).update(eq(USER_ID), any());

        mockMvc.perform(post("/users/{id}", USER_ID).param("role", "STAFF")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("At least one active ADMIN account must remain.")));
    }

    /** Confirms the detail page shows Staff, role, status, and all actions for another account. */
    @Test
    void shouldRenderDetailWithActionsForOtherUser() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", "STF-000003", "STAFF", true));

        mockMvc.perform(get("/users/{id}", USER_ID).with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("STF-000003 - An Le")))
                .andExpect(content().string(containsString("Reset Password")))
                .andExpect(content().string(containsString("/users/" + USER_ID + "/deactivate")))
                .andExpect(content().string(containsString("/users/" + USER_ID + "/edit")));
    }

    /** Confirms the detail page of the administrator's own account hides Deactivate but allows Reset Password. */
    @Test
    void shouldHideDeactivateOnOwnAccount() throws Exception {
        when(userService.findById(SELF_ID)).thenReturn(account(SELF_ID, "admin", null, "ADMIN", true));

        mockMvc.perform(get("/users/{id}", SELF_ID).with(authentication(self())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/users/" + SELF_ID + "/deactivate"))))
                .andExpect(content().string(containsString("Reset Password")));
    }

    /** Confirms an inactive account offers Activate. */
    @Test
    void shouldOfferActivateForInactiveUser() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "STAFF", false));

        mockMvc.perform(get("/users/{id}", USER_ID).with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/users/" + USER_ID + "/activate")));
    }

    /** Confirms activate and deactivate are CSRF-protected POSTs that redirect to the detail page. */
    @Test
    void shouldActivateAndDeactivateWithCsrf() throws Exception {
        when(userService.activate(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "STAFF", true));
        when(userService.deactivate(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "STAFF", false));

        mockMvc.perform(post("/users/{id}/activate", USER_ID).with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/users/" + USER_ID));
        mockMvc.perform(post("/users/{id}/deactivate", USER_ID).with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/users/" + USER_ID));

        verify(userService).activate(USER_ID);
        verify(userService).deactivate(USER_ID);
    }

    /** Confirms a rejected deactivation (self / last admin) is surfaced as a friendly flash message. */
    @Test
    void shouldFlashFriendlyErrorWhenDeactivationRejected() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "You cannot deactivate your own account."))
                .when(userService).deactivate(USER_ID);

        mockMvc.perform(post("/users/{id}/deactivate", USER_ID).with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + USER_ID))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("errorMessage", "You cannot deactivate your own account."));
    }

    /** Confirms password reset renders no password value and delegates on POST. */
    @Test
    void shouldResetPasswordThroughExplicitFlow() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "STAFF", true));

        mockMvc.perform(get("/users/{id}/reset-password", USER_ID).with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"newPassword\"")))
                .andExpect(content().string(not(containsString("value=\""  + "$2"))));

        mockMvc.perform(post("/users/{id}/reset-password", USER_ID)
                        .param("newPassword", "newpassword").param("confirmPassword", "newpassword")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + USER_ID));
        verify(userService).resetPassword(eq(USER_ID), any());
    }

    /** Confirms a rejected reset redisplays a friendly error and never echoes the submitted password. */
    @Test
    void shouldRedisplayResetFormWithoutEchoingPassword() throws Exception {
        when(userService.findById(USER_ID)).thenReturn(account(USER_ID, "an.le", null, "STAFF", true));
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password and confirmation do not match."))
                .when(userService).resetPassword(eq(USER_ID), any());

        mockMvc.perform(post("/users/{id}/reset-password", USER_ID)
                        .param("newPassword", "MySecret123").param("confirmPassword", "Other12345")
                        .with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Password and confirmation do not match.")))
                .andExpect(content().string(not(containsString("MySecret123"))));
    }

    /** Confirms no delete route exists for users. */
    @Test
    void shouldNotExposeUserDeleteRoute() throws Exception {
        mockMvc.perform(delete("/users/{id}", USER_ID).with(user("admin").authorities(manageUser())).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Confirms the literal /users/new route is not swallowed by /users/{id}. */
    @Test
    void shouldRouteNewBeforeVariableId() throws Exception {
        when(userService.linkableStaff(null)).thenReturn(List.of());

        mockMvc.perform(get("/users/new").with(user("admin").authorities(manageUser())))
                .andExpect(status().isOk());
        verify(userService, never()).findById(any());
    }

    private UserResponse account(UUID id, String username, String staffCode, String role, boolean active) {
        return new UserResponse(
                id, username, staffCode == null ? null : UUID.randomUUID(), staffCode,
                staffCode == null ? null : "An Le", true, role, active);
    }

    private StaffResponse staffOption() {
        return new StaffResponse(
                UUID.randomUUID(), "STF-000003", "An", "Le", null, null, null, LocalDate.of(2026, 1, 1), true, null);
    }

    private UsernamePasswordAuthenticationToken self() {
        SessionUserPrincipal principal = new SessionUserPrincipal(
                SELF_ID, "admin", "hash", List.of(new SimpleGrantedAuthority("PERM_MANAGE_USER")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static List<SimpleGrantedAuthority> manageUser() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_USER"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
