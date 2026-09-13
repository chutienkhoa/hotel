package com.example.hotel.controller.customer;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the approved authorization boundary between Guest Management and reservation lookup. */
@WebMvcTest({GuestController.class, GuestLookupController.class})
@Import(GuestAuthorizationTest.MethodSecurityTestConfiguration.class)
class GuestAuthorizationTest {

    private static final UUID GUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestService guestService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms ADMIN and MANAGER can access the Guest Management list and detail operations.
     *
     * @param username representative administrative user
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("guestManagers")
    void shouldAllowAdministrativeRolesToAccessGuestManagement(String username) throws Exception {
        when(guestService.findAll()).thenReturn(List.of());
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());

        mockMvc.perform(get("/api/guests").with(user(username).authorities(manageGuestAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/guests/{id}", GUEST_ID)
                        .with(user(username).authorities(manageGuestAuthority())))
                .andExpect(status().isOk());
    }

    /**
     * Confirms STAFF cannot access Guest Management with reservation view and check-in permissions.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectStaffFromGuestManagement() throws Exception {
        mockMvc.perform(get("/api/guests").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/guests/{id}", GUEST_ID)
                        .with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms reservation creation retains its existing MANAGE_BOOKING-protected guest lookup.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldKeepReservationGuestLookupProtectedByManageBooking() throws Exception {
        when(guestQueryService.findAllForReservationCreation())
                .thenReturn(List.of(new GuestLookupResponse(GUEST_ID, "G000001")));

        mockMvc.perform(get("/api/guests/lookup")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/guests/lookup")
                        .with(user("guest-manager").authorities(manageGuestAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms Guest Management does not expose a REST deletion operation.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldNotExposeGuestDeleteOperation() throws Exception {
        mockMvc.perform(delete("/api/guests/{id}", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Confirms a client-supplied guest code cannot override the value owned by the backend. */
    @Test
    void shouldIgnoreClientSuppliedGuestCode() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any())).thenReturn(guestResponse());
        String body =
                "{\"firstName\":\"First\",\"guestCode\":\"CLIENT-OVERRIDE\"}";

        mockMvc.perform(post("/api/guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestCode").value("G000001"));
    }

    /**
     * Supplies ADMIN and MANAGER as the only roles granted Guest Management permission.
     *
     * @return representative administrative usernames
     */
    private static Stream<Arguments> guestManagers() {
        return Stream.of(Arguments.of("admin"), Arguments.of("manager"));
    }

    /**
     * Builds the authority used by all Guest Management operations.
     *
     * @return the MANAGE_GUEST authority
     */
    private static List<SimpleGrantedAuthority> manageGuestAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_GUEST"));
    }

    /**
     * Builds the existing reservation creation authority.
     *
     * @return the MANAGE_BOOKING authority
     */
    private static List<SimpleGrantedAuthority> manageBookingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    /**
     * Builds the existing STAFF permission set without Guest Management authority.
     *
     * @return the STAFF authorities
     */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /**
     * Creates a representative client-safe guest profile response.
     *
     * @return a guest response for controller testing
     */
    private GuestResponse guestResponse() {
        return new GuestResponse(
                GUEST_ID,
                "G000001",
                "First",
                "Last",
                "guest@example.com",
                "0123456789",
                "Japan",
                null,
                "Tokyo");
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
