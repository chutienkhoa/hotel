package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.config.SecurityConfig;
import com.example.hotel.controller.room.RoomPageController;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.JwtService;
import com.example.hotel.security.SessionUserDetailsService;
import com.example.hotel.service.common.AuthenticationService;
import com.example.hotel.service.room.RoomQueryService;
import com.example.hotel.service.room.RoomService;
import com.example.hotel.service.room.RoomTypeQueryService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the Task 26 table fragment in EN/VI and that the REST API stays English regardless of the UI cookie. */
@WebMvcTest({RoomPageController.class, com.example.hotel.controller.common.AuthController.class})
@Import({SecurityConfig.class, SessionUserDetailsService.class, I18nConfig.class})
@TestPropertySource(properties = "hotel.i18n.default-locale=vi")
class TableAndApiI18nTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private RoomTypeQueryService roomTypeQueryService;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_ROOM"));
    }

    private RoomResponse room() {
        return new RoomResponse(UUID.randomUUID(), "101",
                new RoomTypeResponse(UUID.randomUUID(), "SINGLE", "Single Room"), "1", "AVAILABLE", true);
    }

    /** Confirms summary, pagination and sort-header texts come from the bundles with arguments, in both languages. */
    @Test
    void shouldTranslateTableSummaryAndPagination() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of());
        when(roomQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new PageImpl<>(List.of(room()), PageRequest.of(1, 10), 25));

        mockMvc.perform(get("/rooms").param("page", "1").cookie(language("vi")).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hiển thị 11–11 trên 25")))
                .andExpect(content().string(containsString(">Trước<")))
                .andExpect(content().string(containsString(">Sau<")));
        mockMvc.perform(get("/rooms").param("page", "1").cookie(language("en")).with(manager()))
                .andExpect(content().string(containsString("Showing 11–11 of 25")))
                .andExpect(content().string(containsString(">Previous<")))
                .andExpect(content().string(containsString(">Next<")));
    }

    /** Confirms a zero-result table shows the translated zero summary. */
    @Test
    void shouldTranslateZeroResultSummary() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of());
        when(roomQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/rooms").cookie(language("vi")).with(manager()))
                .andExpect(content().string(containsString("0 kết quả")));
        mockMvc.perform(get("/rooms").cookie(language("en")).with(manager()))
                .andExpect(content().string(containsString("0 results")));
    }

    /** Confirms the REST API response is identical (English) whatever PMS UI language cookie is sent. */
    @Test
    void shouldKeepApiResponsesEnglishRegardlessOfUiLanguage() throws Exception {
        String withVietnamese = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").cookie(language("vi")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String withEnglish = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").cookie(language("en")))
                .andReturn().getResponse().getContentAsString();
        String withoutCookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(json.readTree(withEnglish), json.readTree(withVietnamese));
        assertEquals(json.readTree(withEnglish), json.readTree(withoutCookie));
        assertTrue(withVietnamese.contains("must not be blank"), withVietnamese);
    }

    /** Confirms an unmigrated page rendered by a POST never links its language switch to the POST URL. */
    @Test
    void shouldUseSafeGetTargetForLanguageLinksOnAnyPostRenderedPage() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of());

        mockMvc.perform(post("/rooms").cookie(language("vi")).with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/reservations?lang=en\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("href=\"/rooms?lang="))));
    }

    /** Confirms ?lang= on an API URL neither switches language nor sets a cookie. */
    @Test
    void shouldNotSetLanguageCookieOnApiRequests() throws Exception {
        org.junit.jupiter.api.Assertions.assertNull(mockMvc.perform(post("/api/auth/login?lang=vi")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getHeader("Set-Cookie"));
    }
}
