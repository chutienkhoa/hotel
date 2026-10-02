package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Task33 Batch 1B 403/404/500 templates (spec sec. 12) render safely. Spring Boot's
 * {@code DefaultErrorViewResolver} and Tomcat's auto-registered error pages route a real 403/404/500 to
 * these exact view names in production; that container-level forward is framework behavior this test
 * does not re-verify (MockMvc has no real servlet container, so it cannot simulate a {@code sendError()}
 * forward — a well-known Spring MVC Test limitation). Instead, this test exercises the templates
 * themselves through {@link ErrorViewProbeController}, a throwaway test-only controller that returns
 * each view name directly, confirming they render, show their safe copy, and never leak the framework's
 * own message/trace/path error attributes, which these templates never reference.
 */
@WebMvcTest(ErrorViewProbeController.class)
class ErrorPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRenderPermissionDeniedPageWithSafeCopyOnly() throws Exception {
        mockMvc.perform(get("/test-only/error-403").with(user("staff")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Permission denied")))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("Whitelabel"))));
    }

    @Test
    void shouldRenderPageNotFoundPageWithSafeCopyOnly() throws Exception {
        mockMvc.perform(get("/test-only/error-404").with(user("staff")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Page not found")))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void shouldRenderSystemErrorPageWithSafeCopyOnly() throws Exception {
        mockMvc.perform(get("/test-only/error-500").with(user("staff")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Something went wrong")))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("StackTrace"))));
    }

    @Test
    void shouldRenderErrorPageHomeLinkAndSharedShell() throws Exception {
        mockMvc.perform(get("/test-only/error-404").with(user("staff")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"sidebar-nav\"")))
                .andExpect(content().string(containsString("href=\"/reservations\"")));
    }
}
