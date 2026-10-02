package com.example.hotel.security;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.DashboardPageController;
import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.service.common.DashboardService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the header account area and the existing Spring Security POST /logout through the real MVC chain. */
@WebMvcTest(DashboardPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class LogoutSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms the header account area shows the current username and a CSRF-protected POST logout form.
     * Task33 Batch 1A moved this from the sidebar footer into the shared header (layout/header.html);
     * the markup/behavior otherwise is unchanged from before that move.
     */
    @Test
    void shouldShowUsernameAndLogoutInHeaderAccountArea() throws Exception {
        MockHttpSession session = authenticatedSession();
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(org.mockito.Mockito.mock(DashboardResponse.class));

        mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"header-account-name\">an.le<")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString(">Logout<")));
    }

    /** Confirms POST /logout with CSRF clears the session, redirects to login, and blocks the old session. */
    @Test
    void shouldLogOutWithCsrfAndRequireLoginAgain() throws Exception {
        MockHttpSession session = authenticatedSession();
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(org.mockito.Mockito.mock(DashboardResponse.class));

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        assertTrue(session.isInvalid());
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    /** Confirms logout without a CSRF token is rejected and leaves the session authenticated. */
    @Test
    void shouldRejectLogoutWithoutCsrf() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());

        assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    /** Confirms there is no state-changing GET /logout: a GET never ends the session. */
    @Test
    void shouldNotLogOutOnGet() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(get("/logout").session(session));

        assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    private MockHttpSession authenticatedSession() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "VIEW_REPORT");
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        SessionUserPrincipal principal = new SessionUserPrincipal(
                user.getId(), "an.le", "hash", List.of(new SimpleGrantedAuthority("PERM_VIEW_REPORT")));
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
