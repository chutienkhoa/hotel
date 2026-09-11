package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies reservation read and write authorization for the approved role permissions. */
@WebMvcTest(ReservationController.class)
@Import(ReservationAccessAuthorizationTest.MethodSecurityTestConfiguration.class)
class ReservationAccessAuthorizationTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms that each approved role can read both reservation resources through VIEW_BOOKING.
     *
     * @param username representative username for the tested role
     * @param authorities permissions mapped from that role
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("reservationViewUsers")
    void shouldAllowApprovedRolesToAccessReservationListAndDetail(
            String username, List<SimpleGrantedAuthority> authorities) throws Exception {
        when(reservationQueryService.findAll()).thenReturn(List.of());
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(null);

        mockMvc.perform(get("/api/reservations").with(user(username).authorities(authorities)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reservations/{id}", RESERVATION_ID)
                        .with(user(username).authorities(authorities)))
                .andExpect(status().isOk());
    }

    /**
     * Confirms that only MANAGE_BOOKING continues to authorize confirmation.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("reservationManagers")
    void shouldKeepReservationWritePermissionForAdministrativeRoles(
            String username, List<SimpleGrantedAuthority> authorities) throws Exception {
        when(reservationService.confirm(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CONFIRMED",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .with(user(username).authorities(authorities))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * Confirms that VIEW_BOOKING alone does not grant reservation write access.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldNotGrantReservationWritePermissionToStaff() throws Exception {
        mockMvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(viewBookingAuthority()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms that CHECK_IN remains available to STAFF and unavailable to administrative roles.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldKeepCheckInPermissionForStaffOnly() throws Exception {
        when(reservationService.checkIn(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CHECKED_IN",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/check-in", RESERVATION_ID)
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/check-in", RESERVATION_ID)
                        .with(user("admin").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Supplies the three exact role authority sets that must read reservations.
     *
     * @return representative users and their effective authorities
     */
    private static Stream<Arguments> reservationViewUsers() {
        return Stream.of(
                Arguments.of("admin", manageBookingAndViewAuthorities()),
                Arguments.of("manager", manageBookingAndViewAuthorities()),
                Arguments.of("staff", viewBookingAuthority()));
    }

    /**
     * Supplies the role authority sets that retain the existing MANAGE_BOOKING write permission.
     *
     * @return representative administrative users and their effective authorities
     */
    private static Stream<Arguments> reservationManagers() {
        return Stream.of(
                Arguments.of("admin", manageBookingAndViewAuthorities()),
                Arguments.of("manager", manageBookingAndViewAuthorities()));
    }

    /**
     * Builds the authority used for reservation reads.
     *
     * @return the VIEW_BOOKING authority
     */
    private static List<SimpleGrantedAuthority> viewBookingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /**
     * Builds the authorities shared by ADMIN and MANAGER for reservation operations.
     *
     * @return the MANAGE_BOOKING and VIEW_BOOKING authorities
     */
    private static List<SimpleGrantedAuthority> manageBookingAndViewAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /**
     * Builds the authority reserved for the existing check-in operation.
     *
     * @return the CHECK_IN authority
     */
    private static List<SimpleGrantedAuthority> checkInAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
