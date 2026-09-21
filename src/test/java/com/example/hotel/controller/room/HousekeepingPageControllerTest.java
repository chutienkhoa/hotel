package com.example.hotel.controller.room;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.room.response.HousekeepingRoomResponse;
import com.example.hotel.dto.room.response.HousekeepingWorkspaceResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.room.HousekeepingQueryService;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomQueryService;
import com.example.hotel.service.room.RoomService;
import com.example.hotel.service.room.RoomTypeQueryService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Housekeeping workspace: permission boundary, rendering, actions, redirects and navigation. */
@WebMvcTest({HousekeepingPageController.class, RoomPageController.class, RoomController.class})
@Import({HousekeepingPageControllerTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class HousekeepingPageControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HousekeepingQueryService queryService;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private RoomAvailabilityService roomAvailability;

    @MockitoBean
    private RoomTypeQueryService roomTypeQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms a MANAGE_HOUSEKEEPING-only user opens the workspace with grouped, localized content and no Rooms link. */
    @Test
    void shouldRenderWorkspaceForHousekeepingOnlyUser() throws Exception {
        when(queryService.loadWorkspace()).thenReturn(workspace());

        mockMvc.perform(get("/housekeeping").with(housekeeper()))
                .andExpect(status().isOk())
                .andExpect(view().name("housekeeping/index"))
                .andExpect(content().string(containsString("id=\"count-dirty\">1<")))
                .andExpect(content().string(containsString("id=\"count-cleaning\">1<")))
                .andExpect(content().string(containsString("id=\"count-ready\">1<")))
                .andExpect(content().string(containsString("id=\"count-issues\">2<")))
                .andExpect(content().string(containsString("Start Cleaning")))
                .andExpect(content().string(containsString("Mark Clean")))
                .andExpect(content().string(containsString("Urgent")))
                .andExpect(content().string(containsString("Today")))
                .andExpect(content().string(containsString("Tomorrow")))
                .andExpect(content().string(containsString("25/09/2026")))
                .andExpect(content().string(containsString("No upcoming arrival")))
                .andExpect(content().string(containsString("Out of order")))
                .andExpect(content().string(containsString("/housekeeping/rooms/" + ROOM_ID + "/start-cleaning")))
                .andExpect(content().string(not(containsString("OUT_OF_ORDER"))))
                .andExpect(content().string(containsString("href=\"/housekeeping\"")))
                .andExpect(content().string(not(containsString("href=\"/rooms\""))));
    }

    /** Confirms the Vietnamese labels are used when the language cookie selects Vietnamese. */
    @Test
    void shouldRenderVietnameseLabels() throws Exception {
        when(queryService.loadWorkspace()).thenReturn(workspace());

        mockMvc.perform(get("/housekeeping").with(housekeeper()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bắt đầu dọn")))
                .andExpect(content().string(containsString("Đã dọn xong")))
                .andExpect(content().string(containsString("Khẩn cấp")))
                .andExpect(content().string(containsString("Hôm nay")))
                .andExpect(content().string(containsString("Ngày mai")))
                .andExpect(content().string(containsString("Không có khách sắp đến")));
    }

    /** Confirms only DIRTY rows offer Start Cleaning, only CLEANING rows offer Mark Clean, others no action. */
    @Test
    void shouldOfferOnlyContextualActions() throws Exception {
        when(queryService.loadWorkspace()).thenReturn(new HousekeepingWorkspaceResponse(LocalDate.of(2026, 9, 21),
                List.of(), List.of(), List.of(row("ready", "AVAILABLE", false)),
                List.of(row("issue", "MAINTENANCE", false))));

        mockMvc.perform(get("/housekeeping").with(housekeeper()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("start-cleaning"))))
                .andExpect(content().string(not(containsString("finish-cleaning"))))
                .andExpect(content().string(not(containsString("/rooms/"))));
    }

    /** Confirms users without MANAGE_HOUSEKEEPING (including MANAGE_ROOM-only) are refused and see no link. */
    @Test
    void shouldRefuseUsersWithoutManageHousekeeping() throws Exception {
        mockMvc.perform(get("/housekeeping").with(authorities("PERM_VIEW_BOOKING", "PERM_CHECK_IN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/housekeeping").with(authorities("PERM_MANAGE_ROOM")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/housekeeping/rooms/{id}/start-cleaning", ROOM_ID)
                        .with(authorities("PERM_MANAGE_ROOM")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/housekeeping/rooms/{id}/finish-cleaning", ROOM_ID)
                        .with(authorities("PERM_MANAGE_ROOM")).with(csrf()))
                .andExpect(status().isForbidden());
        verify(roomService, never()).startCleaning(any());
        verify(roomService, never()).finishCleaning(any());
    }

    /** Confirms the sidebar shows Housekeeping for MANAGE_HOUSEKEEPING and Rooms only for MANAGE_ROOM. */
    @Test
    void shouldDriveNavigationFromPermissionsIndependently() throws Exception {
        when(queryService.loadWorkspace()).thenReturn(workspace());
        when(roomQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/housekeeping").with(authorities("PERM_MANAGE_HOUSEKEEPING", "PERM_MANAGE_ROOM")))
                .andExpect(content().string(containsString("href=\"/housekeeping\"")))
                .andExpect(content().string(containsString("href=\"/rooms\"")));
        mockMvc.perform(get("/rooms").with(authorities("PERM_MANAGE_ROOM")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/rooms\"")))
                .andExpect(content().string(not(containsString("href=\"/housekeeping\""))));
    }

    /** Confirms a housekeeping-only user cannot reach Room administration pages or APIs. */
    @Test
    void shouldNotGrantRoomAdministrationToHousekeepingUser() throws Exception {
        mockMvc.perform(get("/rooms").with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms/{id}", ROOM_ID).with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms/new").with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms/{id}/edit", ROOM_ID).with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/rooms").with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/rooms/{id}", ROOM_ID).with(housekeeper())).andExpect(status().isForbidden());
        mockMvc.perform(post("/rooms/{id}/start-maintenance", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/rooms/{id}/mark-out-of-order", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms Start Cleaning delegates to the room service and redirects back to the workspace. */
    @Test
    void shouldStartCleaningAndRedirectToWorkspace() throws Exception {
        mockMvc.perform(post("/housekeeping/rooms/{id}/start-cleaning", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/housekeeping"))
                .andExpect(flash().attributeExists("successMessage"));
        verify(roomService).startCleaning(ROOM_ID);
    }

    /** Confirms Mark Clean delegates to the room service and redirects back to the workspace. */
    @Test
    void shouldFinishCleaningAndRedirectToWorkspace() throws Exception {
        mockMvc.perform(post("/housekeeping/rooms/{id}/finish-cleaning", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/housekeeping"))
                .andExpect(flash().attributeExists("successMessage"));
        verify(roomService).finishCleaning(ROOM_ID);
    }

    /** Confirms a stale action (domain conflict) gives a business error and a refresh, not a failure page. */
    @Test
    void shouldReportStaleActionAsBusinessErrorOnWorkspace() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Room must be DIRTY to start cleaning"))
                .when(roomService).startCleaning(ROOM_ID);

        mockMvc.perform(post("/housekeeping/rooms/{id}/start-cleaning", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/housekeeping"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeCount(1));
    }

    /** Confirms a missing room is reported without leaving the workspace. */
    @Test
    void shouldReportMissingRoomOnWorkspace() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"))
                .when(roomService).finishCleaning(ROOM_ID);

        mockMvc.perform(post("/housekeeping/rooms/{id}/finish-cleaning", ROOM_ID).with(housekeeper()).with(csrf()))
                .andExpect(redirectedUrl("/housekeeping"))
                .andExpect(flash().attribute("errorMessage", "Room not found."));
    }

    /** Confirms the workspace actions keep CSRF protection. */
    @Test
    void shouldRequireCsrfForWorkspaceActions() throws Exception {
        mockMvc.perform(post("/housekeeping/rooms/{id}/start-cleaning", ROOM_ID).with(housekeeper()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor housekeeper() {
        return authorities("PERM_MANAGE_HOUSEKEEPING");
    }

    private static RequestPostProcessor authorities(String... authorities) {
        return user("tester").authorities(java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList());
    }

    private static HousekeepingRoomResponse row(String number, String status, boolean urgent) {
        return new HousekeepingRoomResponse(number.equals("dirty") ? ROOM_ID : UUID.randomUUID(), number, "Single", "1",
                status, null, "NONE", urgent);
    }

    private static HousekeepingWorkspaceResponse workspace() {
        LocalDate today = LocalDate.of(2026, 9, 21);
        return new HousekeepingWorkspaceResponse(
                today,
                List.of(new HousekeepingRoomResponse(ROOM_ID, "101", "Single", "1", "DIRTY", today, "TODAY", true)),
                List.of(new HousekeepingRoomResponse(UUID.randomUUID(), "102", "Single", "1", "CLEANING",
                        today.plusDays(1), "TOMORROW", false)),
                List.of(new HousekeepingRoomResponse(UUID.randomUUID(), "103", "Single", "1", "AVAILABLE",
                        LocalDate.of(2026, 9, 25), "DATE", false)),
                List.of(row("104", "MAINTENANCE", false), row("105", "OUT_OF_ORDER", false)));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
