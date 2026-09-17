package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.RoomHistoryLineResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.math.BigDecimal;
import java.time.Instant;
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

/** Verifies Reservation Detail renders lifecycle-appropriate room and action presentation. */
@WebMvcTest(ReservationPageController.class)
@Import(ReservationDetailLifecycleTest.MethodSecurityTestConfiguration.class)
class ReservationDetailLifecycleTest {

    private static final UUID RESERVATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID GUEST_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROOM_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private StayRoomAssignmentQueryService stayRoomAssignmentQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms a DRAFT reservation still shows the booked-room presentation. */
    @Test
    void shouldShowBookedRoomsForDraft() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("DRAFT"));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(user("admin").authorities(allAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"assigned-rooms-heading\"")))
                .andExpect(content().string(not(containsString("id=\"current-rooms-heading\""))))
                .andExpect(content().string(not(containsString("id=\"room-history-heading\""))));
    }

    /** Confirms CHECKED_IN shows Current rooms sourced from StayRoomAssignment and Room History. */
    @Test
    void shouldShowCurrentRoomsAndHistoryForCheckedIn() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_IN"));
        UUID stayId = UUID.randomUUID();
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(
                new StayResponse(stayId, "CHECKED_IN", Instant.parse("2026-09-16T14:00:00Z"), null));
        when(stayBalanceService.calculate(stayId)).thenReturn(
                new com.example.hotel.service.booking.StayBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(
                List.of(new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, "305", Instant.parse("2026-09-17T03:00:00Z"))));
        when(stayRoomAssignmentQueryService.findHistory(RESERVATION_ID)).thenReturn(List.of(
                new RoomHistoryLineResponse("201", Instant.parse("2026-09-16T14:00:00Z"), Instant.parse("2026-09-17T03:00:00Z"), "Initial Check-in", "staff01"),
                new RoomHistoryLineResponse("305", Instant.parse("2026-09-17T03:00:00Z"), null, "Guest request", "manager01")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("staff").authorities(allAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"assigned-rooms-heading\""))))
                .andExpect(content().string(containsString("id=\"current-rooms-heading\"")))
                .andExpect(content().string(containsString(">305<")))
                .andExpect(content().string(containsString("id=\"room-history-heading\"")))
                .andExpect(content().string(containsString("Initial Check-in")))
                .andExpect(content().string(containsString("Guest request")))
                .andExpect(content().string(not(containsString("GUEST_REQUEST"))))
                .andExpect(content().string(containsString("status-badge--current")))
                .andExpect(content().string(containsString("17/09/2026")))
                .andExpect(content().string(not(containsString("2026-09-17T"))));
    }

    /** Confirms Change Room is hidden without PERM_CHANGE_ROOM even while Current rooms render. */
    @Test
    void shouldHideChangeRoomWithoutPermission() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_IN"));
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(
                List.of(new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, "305", Instant.parse("2026-09-17T03:00:00Z"))));
        when(stayRoomAssignmentQueryService.findHistory(RESERVATION_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"current-rooms-heading\"")))
                .andExpect(content().string(not(containsString("Change Room"))));
    }

    /** Confirms CHECKED_OUT hides Current rooms and the empty Available actions card, but keeps Room History. */
    @Test
    void shouldHideCurrentRoomsAndActionsForCheckedOut() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_OUT"));
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of());
        when(stayRoomAssignmentQueryService.findHistory(RESERVATION_ID)).thenReturn(List.of(
                new RoomHistoryLineResponse("201", Instant.parse("2026-09-16T14:00:00Z"), Instant.parse("2026-09-18T10:00:00Z"), "Initial Check-in", "staff01")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(allAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"current-rooms-heading\""))))
                .andExpect(content().string(not(containsString("id=\"assigned-rooms-heading\""))))
                .andExpect(content().string(containsString("id=\"room-history-heading\"")))
                .andExpect(content().string(not(containsString("id=\"actions-heading\""))))
                .andExpect(content().string(not(containsString("No state-changing actions are available."))));
    }

    /** Builds a Reservation Detail response for one lifecycle status with one booked room. */
    private ReservationDetailResponse reservation(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260917-000001",
                GUEST_ID,
                "GUEST-001",
                status,
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 18),
                BigDecimal.TEN,
                "VND",
                null,
                List.of(new ReservationRoomResponse(
                        ROOM_ID, "201", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), BigDecimal.TEN, BigDecimal.TEN)));
    }

    /** Builds the full authority set used by tests that do not focus on a specific permission gate. */
    private static List<SimpleGrantedAuthority> allAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"),
                new SimpleGrantedAuthority("PERM_CHANGE_ROOM"),
                new SimpleGrantedAuthority("PERM_VIEW_REPORT"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
