package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayBalance;
import java.time.Instant;
import java.time.LocalDate;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
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
@WebMvcTest({ReservationController.class, ReservationPageController.class})
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
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

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
     * Confirms CHECK_OUT is required by both REST and CSRF-protected browser check-out operations.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRequireCheckOutPermissionAndCsrfForCheckout() throws Exception {
        when(reservationService.checkOut(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CHECKED_OUT",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** Confirms a payment manager can discover the Folio link for a Reservation with a Stay. */
    @Test
    void shouldShowFolioLinkToManagePaymentUserForCheckedInReservation() throws Exception {
        stubCheckedInReservation(BigDecimal.ZERO);

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(viewAndManagePaymentAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("View Folio")));
    }

    /** Confirms a check-out-only user receives readiness without detailed Folio financial access. */
    @Test
    void shouldShowNonFinancialReadinessToCheckOutOnlyUser() throws Exception {
        stubCheckedInReservation(BigDecimal.TEN);

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("staff").authorities(viewAndCheckOutAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment required before check-out.")))
                .andExpect(content().string(not(containsString("View Folio"))))
                .andExpect(content().string(not(containsString("Total Charges"))));
    }

    /** Confirms VND Reservation totals and assigned-room values use grouped zero-decimal display. */
    @Test
    void shouldFormatVndAmountsOnReservationDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CONFIRMED",
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                new BigDecimal("123456789.000000"),
                "VND",
                null,
                List.of(new ReservationRoomResponse(
                        UUID.randomUUID(),
                        "101",
                        LocalDate.of(2026, 9, 11),
                        LocalDate.of(2026, 9, 12),
                        new BigDecimal("5500000.000000"),
                        new BigDecimal("1100000.000000")))));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("123,456,789 VND")))
                .andExpect(content().string(containsString("5,500,000 VND")))
                .andExpect(content().string(containsString("1,100,000 VND")));
    }

    /** Supplies the checked-in Reservation, Stay, and authoritative balance required by detail rendering. */
    private void stubCheckedInReservation(BigDecimal outstanding) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CHECKED_IN",
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                BigDecimal.TEN,
                "JPY",
                null,
                List.of()));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(new StayResponse(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "CHECKED_IN",
                Instant.parse("2026-09-11T10:00:00Z"),
                null));
        when(stayBalanceService.calculate(UUID.fromString("22222222-2222-2222-2222-222222222222")))
                .thenReturn(new StayBalance(BigDecimal.TEN, BigDecimal.TEN.subtract(outstanding), outstanding));
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

    /** Builds the authority set required to view reservation detail and detailed Folio access. */
    private static List<SimpleGrantedAuthority> viewAndManagePaymentAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    /** Builds the authority set required for reservation detail and check-out readiness. */
    private static List<SimpleGrantedAuthority> viewAndCheckOutAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /**
     * Builds the authority reserved for the existing check-in operation.
     *
     * @return the CHECK_IN authority
     */
    private static List<SimpleGrantedAuthority> checkInAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    /**
     * Builds the authority reserved for the approved check-out operation.
     *
     * @return the CHECK_OUT authority
     */
    private static List<SimpleGrantedAuthority> checkOutAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
