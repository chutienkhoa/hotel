package com.example.hotel.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.common.LoginPageController;
import com.example.hotel.repository.common.AppUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the runtime language behaviour through the real security chain. The test environment
 * normally defaults to English; this class pins the RUNTIME default (Vietnamese) explicitly.
 */
@WebMvcTest(LoginPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class, I18nConfig.class})
@TestPropertySource(properties = "hotel.i18n.default-locale=vi")
class LocaleSwitchingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    /** Confirms the runtime default is Vietnamese, with the document language and switcher to match. */
    @Test
    void shouldRenderVietnameseByDefaultWithMatchingHtmlLang() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andExpect(content().string(containsString("Đăng nhập")))
                .andExpect(content().string(containsString("Tên đăng nhập")))
                .andExpect(content().string(containsString("href=\"/login?lang=en\"")))
                .andExpect(content().string(containsString("href=\"/login?lang=vi\"")));
    }

    /** Confirms the browser's Accept-Language never selects the initial PMS language. */
    @Test
    void shouldIgnoreAcceptLanguageHeader() throws Exception {
        mockMvc.perform(get("/login").header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(content().string(containsString("<html lang=\"vi\"")));
    }

    /** Confirms ?lang=en renders English immediately and persists the choice in a safe cookie. */
    @Test
    void shouldSwitchToEnglishAndSetCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Sign in")))
                .andReturn();

        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("pms-lang=en"), cookie);
        assertTrue(cookie.contains("SameSite=Lax") && cookie.contains("HttpOnly"), cookie);
    }

    /** Confirms the cookie carries the language to later requests, in both directions. */
    @Test
    void shouldPersistLanguageThroughTheCookie() throws Exception {
        mockMvc.perform(get("/login").cookie(language("en")))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(not(containsString("Đăng nhập"))));
        mockMvc.perform(get("/login").cookie(language("vi")))
                .andExpect(content().string(containsString("<html lang=\"vi\"")));
    }

    /** Confirms unsupported languages and tampered cookies never create arbitrary locale behaviour. */
    @Test
    void shouldRejectUnsupportedLanguagesAndTamperedCookies() throws Exception {
        MvcResult unsupported = mockMvc.perform(get("/login").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"vi\"")))
                .andReturn();
        assertNull(unsupported.getResponse().getHeader("Set-Cookie"));

        mockMvc.perform(get("/login").param("lang", "../../etc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"vi\"")));
        mockMvc.perform(get("/login").cookie(language("fr")))
                .andExpect(content().string(containsString("<html lang=\"vi\"")));
        mockMvc.perform(get("/login").cookie(language("../../x")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"vi\"")));
    }

    /** Confirms the language survives logout and the next login page (the cookie is not tied to the session). */
    @Test
    void shouldKeepLanguageAcrossLogoutAndLogin() throws Exception {
        mockMvc.perform(post("/logout").cookie(language("en")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
        mockMvc.perform(get("/login").param("logout", "").cookie(language("en")))
                .andExpect(content().string(containsString("<html lang=\"en\"")))
                .andExpect(content().string(containsString("Sign in")));
    }

    /** Confirms the login failure message is translated. */
    @Test
    void shouldTranslateLoginFailureMessage() throws Exception {
        mockMvc.perform(get("/login").param("error", "").cookie(language("vi")))
                .andExpect(content().string(containsString("Tên đăng nhập hoặc mật khẩu không đúng.")));
        mockMvc.perform(get("/login").param("error", "").cookie(language("en")))
                .andExpect(content().string(containsString("Invalid username or password.")));
    }
}
