package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.DashboardService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Task33 Batch 1A app-shell foundation: the renamed brand, the header's mobile nav toggle
 * and account dropdown, the mobile backdrop, and that the sidebar items left deliberately unchanged
 * (Rooms, Housekeeping, the transitional Check-in/Check-out) still render. Uses {@code /dashboard}
 * purely as a cheap real page to render the shared shell fragments against, the same pattern
 * {@link LogoutSecurityTest} and {@link DashboardPageControllerTest} already use.
 */
@WebMvcTest(DashboardPageController.class)
@Import(AppShellTest.MethodSecurityTestConfiguration.class)
class AppShellTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private JwtService jwtService;

    /** Grants every navigation-relevant authority, so the whole shell renders fully for inspection. */
    private static List<SimpleGrantedAuthority> everyNavigationAuthority() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_REPORT"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"),
                new SimpleGrantedAuthority("PERM_MANAGE_ROOM"),
                new SimpleGrantedAuthority("PERM_MANAGE_HOUSEKEEPING"),
                new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"),
                new SimpleGrantedAuthority("PERM_MANAGE_ADDITIONAL_REVENUE"),
                new SimpleGrantedAuthority("PERM_MANAGE_STAFF"),
                new SimpleGrantedAuthority("PERM_MANAGE_USER"));
    }

    /**
     * Confirms the renamed brand appears once, in the header, and is not duplicated in the sidebar
     * (Task33 Dashboard visual polish removed the sidebar's own brand block).
     */
    @Test
    void shouldRenderSunsetHouseBrandInHeaderOnlyNotDuplicatedInSidebar() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"brand\"")))
                .andExpect(content().string(containsString(">Sunset House</span>")))
                .andExpect(content().string(not(containsString("Hotel Management"))))
                .andExpect(content().string(not(containsString("sidebar-brand"))));
    }

    /** Confirms the shared head supplies the hotel favicon and the "Sunset Hotel | <Page>" title, whatever the locale. */
    @Test
    void shouldRenderHotelFaviconAndBrandedTitle() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Sunset Hotel | Dashboard</title>")))
                .andExpect(content().string(containsString("href=\"/images/branding/sunset-hotel-favicon.png?v=2\"")))
                .andExpect(content().string(containsString("href=\"/images/branding/sunset-hotel-apple-touch-icon.png?v=2\"")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("(?s)^(?:(?!rel=\"icon\").)*rel=\"icon\"(?:(?!rel=\"icon\").)*$")))
                .andExpect(content().string(not(containsString("rel=\"shortcut icon\""))));
    }

    /** Confirms only the page name is localized: the brand stays first and unchanged in Vietnamese. */
    @Test
    void shouldLocalizeOnlyThePageNameInTheTitle() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority()))
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("<title>Sunset Hotel | Bảng điều khiển</title>")));
    }

    /** Confirms the mobile nav toggle targets the sidebar and starts closed, with a localized label. */
    @Test
    void shouldRenderMobileNavToggleControllingTheSidebar() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"header-nav-toggle\"")))
                .andExpect(content().string(containsString("aria-controls=\"sidebar-nav\"")))
                .andExpect(content().string(containsString("aria-expanded=\"false\"")))
                .andExpect(content().string(containsString("aria-label=\"Open navigation menu\"")))
                .andExpect(content().string(containsString("data-label-open=\"Open navigation menu\"")))
                .andExpect(content().string(containsString("data-label-close=\"Close navigation menu\"")))
                .andExpect(content().string(containsString("id=\"sidebar-nav\"")))
                .andExpect(content().string(containsString("id=\"sidebar-backdrop\"")))
                // Regression guard: th:replace substitutes the whole host tag, so a class placed directly
                // on the <svg> host (the original, buggy markup) never reaches the rendered page, and the
                // aria-expanded CSS below never matches anything -- both icons then show at once. The class
                // must live on a real wrapping element that th:replace does not touch.
                .andExpect(content().string(containsString("<span class=\"header-nav-toggle-icon-open\">")))
                .andExpect(content().string(containsString("<span class=\"header-nav-toggle-icon-close\">")));
    }

    /**
     * Confirms the header account dropdown exposes the username and logout form only. The global
     * header language-switcher redesign moved the language control out of this dropdown into its own
     * compact flag trigger/popover immediately left of the avatar (see
     * {@link #shouldRenderHeaderLanguageSwitcherOutsideTheAccountDropdown()}), so the Admin dropdown
     * must no longer contain it.
     */
    @Test
    void shouldRenderHeaderAccountDropdown() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        String body = mockMvc.perform(get("/dashboard").with(user("an.le").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"header-account-trigger\"")))
                .andExpect(content().string(containsString("aria-haspopup=\"menu\"")))
                .andExpect(content().string(containsString("aria-controls=\"header-account-menu\"")))
                .andExpect(content().string(containsString("class=\"header-account-name\">an.le<")))
                .andExpect(content().string(containsString("class=\"header-account-initial\">A<")))
                .andExpect(content().string(containsString("id=\"header-account-menu\"")))
                // Bare boolean `hidden` (not hidden="hidden"), so it never collides with the Reservation
                // form's own conditional hidden="hidden" OTA-reference-field toggling elsewhere on the page.
                .andExpect(content().string(containsString("class=\"header-account-menu\" hidden")))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andReturn().getResponse().getContentAsString();

        String accountMenu = body.substring(
                body.indexOf("id=\"header-account-menu\""), body.indexOf("</header>"));
        org.junit.jupiter.api.Assertions.assertFalse(
                accountMenu.contains("language-switch"),
                "the Admin dropdown must no longer contain the VI | EN language switcher");
    }

    /**
     * Confirms the global header language switcher now lives as its own compact flag control,
     * immediately left of the account avatar, outside the Admin dropdown -- opening a popover with
     * both languages, the active one marked, built on the existing pms-lang/?lang= mechanism.
     */
    @Test
    void shouldRenderHeaderLanguageSwitcherOutsideTheAccountDropdown() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"header-language-trigger\"")))
                .andExpect(content().string(containsString("aria-haspopup=\"menu\"")))
                .andExpect(content().string(containsString("aria-controls=\"header-language-menu\"")))
                .andExpect(content().string(containsString("class=\"header-language-flag\"")))
                .andExpect(content().string(containsString("id=\"header-language-menu\"")))
                .andExpect(content().string(containsString("class=\"header-language-menu\" hidden")))
                .andExpect(content().string(containsString("lang=vi")))
                .andExpect(content().string(containsString("lang=en")))
                .andExpect(content().string(containsString(">Tiếng Việt<")))
                .andExpect(content().string(containsString(">English<")));
    }

    /**
     * Confirms the active language is taken from the request's resolved locale: with Vietnamese current, only the
     * Vietnamese row is marked active (filled indicator and aria-current), and the English row is not.
     */
    @Test
    void shouldMarkVietnameseAsTheActiveHeaderLanguage() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        String body = mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority()))
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String vi = languageOption(body, "vi");
        String en = languageOption(body, "en");
        org.junit.jupiter.api.Assertions.assertTrue(vi.contains("header-language-option-indicator is-active"), vi);
        org.junit.jupiter.api.Assertions.assertTrue(vi.contains("aria-current=\"true\""), vi);
        org.junit.jupiter.api.Assertions.assertFalse(en.contains("is-active"), en);
        org.junit.jupiter.api.Assertions.assertFalse(en.contains("aria-current"), en);
    }

    /** Confirms that switching the request locale to English moves the active marker to the English row. */
    @Test
    void shouldMarkEnglishAsTheActiveHeaderLanguage() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        String body = mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority()))
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "en")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String vi = languageOption(body, "vi");
        String en = languageOption(body, "en");
        org.junit.jupiter.api.Assertions.assertTrue(en.contains("header-language-option-indicator is-active"), en);
        org.junit.jupiter.api.Assertions.assertTrue(en.contains("aria-current=\"true\""), en);
        org.junit.jupiter.api.Assertions.assertFalse(vi.contains("is-active"), vi);
        org.junit.jupiter.api.Assertions.assertFalse(vi.contains("aria-current"), vi);
    }

    /**
     * Confirms the header clock is an empty, hidden slot filled by the browser script: no server-rendered time, no
     * live region, and it sits immediately before the language control.
     */
    @Test
    void shouldRenderClientClockSlotWithoutServerTime() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        String body = mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"header-clock\" id=\"header-clock\" hidden")))
                .andExpect(content().string(containsString("<time id=\"header-clock-time\"></time>")))
                .andExpect(content().string(containsString("/js/common/client-clock.js")))
                .andExpect(content().string(not(containsString("aria-live"))))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                body.indexOf("id=\"header-clock\"") < body.indexOf("id=\"header-language-trigger\""),
                "the clock must sit immediately left of the language control");
    }

    /** Confirms the header has exactly one language control, so the flag popover is the only switcher in the shell. */
    @Test
    void shouldRenderExactlyOneHeaderLanguageControl() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        String body = mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String header = body.substring(body.indexOf("<header"), body.indexOf("</header>"));
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(header, "id=\"header-language-trigger\""));
        org.junit.jupiter.api.Assertions.assertEquals(1, countOccurrences(header, "id=\"header-language-menu\""));
        org.junit.jupiter.api.Assertions.assertFalse(header.contains("language-switch"));
    }

    /** Returns one language row of the header popover, from its menuitem link to the closing tag. */
    private static String languageOption(String body, String lang) {
        int start = body.indexOf("hreflang=\"" + lang + "\" lang=\"" + lang + "\" role=\"menuitem\"");
        return body.substring(start, body.indexOf("</a>", start));
    }

    private static int countOccurrences(String body, String token) {
        int count = 0;
        for (int index = body.indexOf(token); index >= 0; index = body.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    /**
     * Confirms the transitional Check-in/Check-out links Batch 1A left unchanged still render, and that
     * Batch 1B's final HOTEL nesting is in place: "Rooms" is a non-link group label (no {@code
     * sidebar-nav-link} class, per spec sec. 7.1a) with Room List/Housekeeping as its linked children.
     */
    @Test
    void shouldStillRenderUnchangedGroupsAndFrontDeskWithoutTransitionalLinks() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("class=\"nav-group-label\""))))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-rooms\""))))
                .andExpect(content().string(containsString("nav-room-list")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-housekeeping\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-front-desk\"")))
                .andExpect(content().string(not(containsString("nav-check-in"))))
                .andExpect(content().string(not(containsString("nav-check-out"))))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-staff\"")));
    }

    /** Confirms the shared shell renders the desktop collapse control, its state script, and ROOMS as a section heading. */
    @Test
    void shouldRenderSidebarCollapseControlAndRoomsSection() throws Exception {
        when(dashboardService.getDashboard(org.mockito.ArgumentMatchers.any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/dashboard").with(user("admin").authorities(everyNavigationAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"sidebar-collapse-toggle\"")))
                .andExpect(content().string(containsString("/js/common/sidebar-collapse.js")))
                .andExpect(content().string(containsString("<p class=\"nav-section-label\">Rooms</p>")));
    }

    /** Creates the empty approved Dashboard shape used by MVC controller tests. */
    private DashboardResponse emptyDashboard() {
        return new DashboardResponse(
                LocalDate.of(2026, 9, 16), null, 0L, null, null,
                List.of(), List.of(), 0L, List.of(), List.of(), List.of());
    }

    /** Enables method-security interception for shell MVC tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
