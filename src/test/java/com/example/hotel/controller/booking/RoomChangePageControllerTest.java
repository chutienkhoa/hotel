package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.RoomChangeReviewResponse;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.RoomChangeService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Room Change MVC authorization and CSRF requirements. */
@WebMvcTest(RoomChangePageController.class)
@Import(RoomChangePageControllerTest.MethodSecurityTestConfiguration.class)
class RoomChangePageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomChangeService roomChangeService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN, MANAGER, and STAFF can all access the Room Change form. */
    @Test
    void shouldAllowAdminManagerAndStaffToAccessRoomChangeForm() throws Exception {
        when(roomChangeService.candidateRooms(RESERVATION_ID, ROOM_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("admin").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("manager").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms a user without CHANGE_ROOM cannot access the Room Change form. */
    @Test
    void shouldForbidUserWithoutChangeRoomPermission() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms MANAGE_BOOKING alone does not substitute for CHANGE_ROOM. */
    @Test
    void shouldForbidManageBookingAloneWithoutChangeRoom() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("editor").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms CHECK_IN alone does not substitute for CHANGE_ROOM. */
    @Test
    void shouldForbidCheckInAloneWithoutChangeRoom() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("frontdesk").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the review POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForReviewPost() throws Exception {
        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/review", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the confirm POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForConfirmPost() throws Exception {
        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/confirm", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a structurally valid review submission renders the Review template. */
    @Test
    void shouldRenderReviewOnValidSubmission() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RoomChangeReviewResponse(
                        RESERVATION_ID,
                        "R20260917-000001",
                        ROOM_ID,
                        "201",
                        targetRoomId,
                        "305",
                        RoomChangeReason.GUEST_REQUEST,
                        null,
                        LocalDate.of(2026, 9, 20)));

        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/review", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority()))
                        .with(csrf())
                        .param("targetRoomId", targetRoomId.toString())
                        .param("reason", "GUEST_REQUEST"))
                .andExpect(status().isOk());
    }

    /** Builds the CHANGE_ROOM authority. */
    private static List<SimpleGrantedAuthority> changeRoomAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHANGE_ROOM"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
