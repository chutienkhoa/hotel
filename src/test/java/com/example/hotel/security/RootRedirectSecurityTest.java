package com.example.hotel.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.DashboardPageController;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.service.common.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the root redirect works with the real MVC security chain and leaves authentication rules intact. */
@WebMvcTest(DashboardPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class RootRedirectSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms an unauthenticated root request is not permitted: it is sent on to the login page. */
    @Test
    void shouldRequireAuthenticationForRoot() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    /** Confirms the Dashboard itself still redirects unauthenticated users to login. */
    @Test
    void shouldStillRequireAuthenticationForDashboard() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    /** Confirms an authenticated user is redirected from the root to the Dashboard, which then renders. */
    @Test
    void shouldRedirectAuthenticatedRootToDashboard() throws Exception {
        mockMvc.perform(get("/").with(user("admin").authorities(new SimpleGrantedAuthority("PERM_VIEW_REPORT"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    /** Confirms Dashboard authorization is unchanged: an authenticated user without VIEW_REPORT is forbidden. */
    @Test
    void shouldKeepDashboardAuthorization() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(null);

        mockMvc.perform(get("/dashboard").with(user("staff").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }
}
