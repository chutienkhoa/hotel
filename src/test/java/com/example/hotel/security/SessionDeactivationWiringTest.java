package com.example.hotel.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.UserPageController;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.service.common.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies, through the real MVC security chain, that {@link ActiveUserSessionFilter} is wired in:
 * a deactivated account's already-authenticated session is rejected on its next request, and
 * role changes take effect immediately.
 */
@WebMvcTest(UserPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class SessionDeactivationWiringTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserService userService;

    /** Confirms a session whose account was deactivated is redirected to login on the next request. */
    @Test
    void shouldRejectExistingSessionAfterDeactivation() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_USER");
        user.deactivate();
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        mockMvc.perform(get("/users").with(authentication(session(user, "PERM_MANAGE_USER"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /** Confirms an active account with MANAGE_USER keeps working. */
    @Test
    void shouldAllowActiveSessionWithManageUser() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_USER");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        mockMvc.perform(get("/users").with(authentication(session(user, "PERM_MANAGE_USER"))))
                .andExpect(status().isOk());
    }

    /** Confirms a role change applies on the next request: a stale MANAGE_USER session authority is ignored. */
    @Test
    void shouldApplyCurrentRolePermissionsInsteadOfStaleSessionAuthorities() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_ROOM");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

        mockMvc.perform(get("/users").with(authentication(session(user, "PERM_MANAGE_USER"))))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken session(AppUser user, String authority) {
        SessionUserPrincipal principal = new SessionUserPrincipal(
                user.getId(), user.getUsername(), user.getPasswordHash(), List.of(new SimpleGrantedAuthority(authority)));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
