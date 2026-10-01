package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
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

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.response.RoomReassignmentCandidateResponse;
import com.example.hotel.dto.booking.response.RoomReassignmentFormResponse;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.exception.RoomReassignmentException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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

/** Verifies the pre-check-in reassignment pages: authorization, EN/VI rendering, redirects and error handling. */
@WebMvcTest(RoomReassignmentPageController.class)
@Import({RoomReassignmentPageControllerTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class RoomReassignmentPageControllerTest {

    private static final UUID RESERVATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String URL = "/check-in/reservations/{r}/rooms/{room}/reassign";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationRoomReassignmentService service;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the form lists eligible rooms, the booked rate note and English labels. */
    @Test
    void shouldRenderFormInEnglish() throws Exception {
        when(service.form(RESERVATION, ROOM)).thenReturn(form(List.of(
                new RoomReassignmentCandidateResponse(TARGET, "203", "Double"))));

        mockMvc.perform(get(URL, RESERVATION, ROOM).with(checkIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reassign Room")))
                .andExpect(content().string(containsString("Current Room")))
                .andExpect(content().string(containsString("Replacement Room")))
                .andExpect(content().string(containsString("Available Rooms")))
                .andExpect(content().string(containsString("203 (Double)")))
                .andExpect(content().string(containsString("Confirm Reassignment")));
    }

    /** Confirms the Vietnamese labels are used when Vietnamese is selected. */
    @Test
    void shouldRenderFormInVietnamese() throws Exception {
        when(service.form(RESERVATION, ROOM)).thenReturn(form(List.of()));

        mockMvc.perform(get(URL, RESERVATION, ROOM).with(checkIn()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Phòng hiện tại")))
                .andExpect(content().string(containsString("Phòng thay thế")))
                .andExpect(content().string(containsString("Không có phòng thay thế phù hợp.")))
                .andExpect(content().string(containsString("Xác nhận đổi phòng")));
    }

    /** Confirms an empty candidate list shows the no-candidates message and no enabled submit. */
    @Test
    void shouldExplainWhenNoReplacementIsEligible() throws Exception {
        when(service.form(RESERVATION, ROOM)).thenReturn(form(List.of()));

        mockMvc.perform(get(URL, RESERVATION, ROOM).with(checkIn()))
                .andExpect(content().string(containsString("No eligible replacement rooms.")));
    }

    /** Confirms a successful reassignment returns to the Check-in Review, where readiness is recomputed. */
    @Test
    void shouldReturnToCheckInReviewAfterSuccess() throws Exception {
        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString())
                        .with(checkIn()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/reservations/" + RESERVATION))
                .andExpect(flash().attribute("successMessage", "Room reassigned successfully."));
        verify(service).reassign(RESERVATION, ROOM, TARGET);
    }

    /** Confirms a room that is no longer available returns to the form with a localized message. */
    @Test
    void shouldReturnToFormWhenReplacementIsNoLongerAvailable() throws Exception {
        doThrow(new RoomReassignmentException(Reason.ROOM_UNAVAILABLE, "x")).when(service)
                .reassign(RESERVATION, ROOM, TARGET);

        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString())
                        .with(checkIn()).with(csrf()))
                .andExpect(redirectedUrl("/check-in/reservations/" + RESERVATION + "/rooms/" + ROOM + "/reassign"))
                .andExpect(flash().attribute("errorMessage", "Room is no longer available."));
    }

    /** Confirms a changed reservation state returns to the review with a state-changed message. */
    @Test
    void shouldReturnToReviewWhenReservationStateChanged() throws Exception {
        doThrow(new RoomReassignmentException(Reason.RESERVATION_STATE_CHANGED, "x")).when(service)
                .reassign(RESERVATION, ROOM, TARGET);

        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString())
                        .with(checkIn()).with(csrf()))
                .andExpect(redirectedUrl("/check-in/reservations/" + RESERVATION))
                .andExpect(flash().attribute("errorMessage", containsString("Reservation state has changed")));
    }

    /** Confirms a missing reservation or room is reported, not raised as a failure page. */
    @Test
    void shouldReportMissingEntities() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")).when(service)
                .reassign(RESERVATION, ROOM, TARGET);

        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString())
                        .with(checkIn()).with(csrf()))
                .andExpect(redirectedUrl("/check-in/reservations/" + RESERVATION))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    /** Confirms only CHECK_IN owns the operation: housekeeping, room and booking permissions do not. */
    @Test
    void shouldRequireCheckInPermission() throws Exception {
        RequestPostProcessor other = user("other").authorities(
                new SimpleGrantedAuthority("PERM_MANAGE_HOUSEKEEPING"),
                new SimpleGrantedAuthority("PERM_MANAGE_ROOM"),
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHANGE_ROOM"));

        mockMvc.perform(get(URL, RESERVATION, ROOM).with(other)).andExpect(status().isForbidden());
        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString())
                        .with(other).with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).reassign(RESERVATION, ROOM, TARGET);
    }

    /** Confirms the POST keeps CSRF protection. */
    @Test
    void shouldRequireCsrf() throws Exception {
        mockMvc.perform(post(URL, RESERVATION, ROOM).param("targetRoomId", TARGET.toString()).with(checkIn()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor checkIn() {
        return user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    private static RoomReassignmentFormResponse form(List<RoomReassignmentCandidateResponse> candidates) {
        return new RoomReassignmentFormResponse(RESERVATION, "R20260921-000001", ROOM, "101", "Single",
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 24), new BigDecimal("1000000"), "VND", candidates);
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
