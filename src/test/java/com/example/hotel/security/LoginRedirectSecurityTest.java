package com.example.hotel.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.DashboardPageController;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.service.common.DashboardService;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies that successful browser form login always lands on the Dashboard through the real MVC chain. */
@WebMvcTest(DashboardPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class LoginRedirectSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms a login started directly from /login lands on the Dashboard. */
    @Test
    void shouldRedirectSuccessfulLoginToDashboard() throws Exception {
        givenActiveUser();

        mockMvc.perform(formLogin("/login").user("an.le").password("secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    /** Confirms a previously requested protected URL is not restored after login. */
    @Test
    void shouldIgnoreSavedRequestAfterLogin() throws Exception {
        givenActiveUser();
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/reservations").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        mockMvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "an.le").param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    /** Confirms a failed login stays on the login page. */
    @Test
    void shouldStayOnLoginWhenCredentialsAreInvalid() throws Exception {
        givenActiveUser();

        mockMvc.perform(formLogin("/login").user("an.le").password("wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    private void givenActiveUser() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "VIEW_REPORT");
        Field hash = AppUser.class.getDeclaredField("passwordHash");
        hash.setAccessible(true);
        hash.set(user, passwordEncoder.encode("secret"));
        when(appUserRepository.findByUsernameIgnoreCase("an.le")).thenReturn(Optional.of(user));
    }
}
