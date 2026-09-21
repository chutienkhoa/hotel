package com.example.hotel.controller.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.room.RoomQueryService;
import com.example.hotel.service.room.RoomService;
import com.example.hotel.service.room.RoomTypeQueryService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;

/** Verifies Room Management authorization and the unchanged Reservation room-lookup boundary. */
@WebMvcTest({RoomController.class, RoomLookupController.class, RoomPageController.class, RoomTypeController.class})
@Import({RoomAuthorizationTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class})
class RoomAuthorizationTest {

    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_TYPE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private com.example.hotel.service.room.RoomAvailabilityService roomAvailability;

    @MockitoBean
    private RoomTypeQueryService roomTypeQueryService;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms ADMIN and MANAGER can access Room Management list, detail, and RoomType reads.
     *
     * @param username representative administrative user
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("roomManagers")
    void shouldAllowAdministrativeRolesToAccessRoomManagement(String username) throws Exception {
        when(roomService.findAll()).thenReturn(List.of(roomResponse()));
        when(roomService.findById(ROOM_ID)).thenReturn(roomResponse());
        when(roomTypeQueryService.findAll()).thenReturn(List.of(roomTypeResponse()));
        when(roomQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(roomResponse()), org.springframework.data.domain.PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/rooms").with(user(username).authorities(manageRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rooms/{id}", ROOM_ID)
                        .with(user(username).authorities(manageRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/room-types").with(user(username).authorities(manageRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rooms").with(user(username).authorities(manageRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(view().name("room/list"))
                .andExpect(model().attribute("canManageRoom", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rooms\"")));
    }

    /** Confirms the Room list renders all four filters and reuses the shared pagination markup. */
    @Test
    void shouldRenderRoomFiltersAndSharedPaginationMarkup() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of(roomTypeResponse()));
        when(roomQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(roomResponse()), org.springframework.data.domain.PageRequest.of(0, 10), 11));

        mockMvc.perform(get("/rooms").with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"roomNumber\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"roomTypeId\" name=\"roomTypeId\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"floor\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"status\" name=\"status\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All room types")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All statuses")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"pagination room-pagination\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment--current")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rooms\">Reset</a>")));
    }

    /** Confirms active Room filters are preserved in a pagination link. */
    @Test
    void shouldPreserveActiveFiltersInRoomPaginationLinks() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of(roomTypeResponse()));
        when(roomQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(roomResponse()), org.springframework.data.domain.PageRequest.of(0, 10), 11));

        mockMvc.perform(get("/rooms")
                        .param("roomNumber", "101")
                        .param("status", "AVAILABLE")
                        .with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/rooms?roomNumber=101&amp;status=AVAILABLE&amp;page=1\"")));
    }

    /** Confirms a zero-result Room filter shows the empty state and omits pagination. */
    @Test
    void shouldRenderZeroResultRoomFilterWithoutPagination() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of(roomTypeResponse()));
        when(roomQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/rooms").param("floor", "99")
                        .with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0 results")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "No rooms match the current filters.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"99\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "class=\"pagination room-pagination\""))));
    }

    /**
     * Confirms STAFF cannot access Room Management with only reservation-view and check-in/out rights.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectStaffFromRoomManagement() throws Exception {
        mockMvc.perform(get("/api/rooms").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/rooms/{id}", ROOM_ID).with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/room-types").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    /** Confirms navigation hides the Rooms link for users without Room Management permission. */
    @Test
    void shouldExposeFalseRoomNavigationFlagWithoutManageRoomPermission() {
        ExtendedModelMap model = new ExtendedModelMap();
        new NavigationModelAdvice().addNavigationAttributes(
                model,
                new UsernamePasswordAuthenticationToken("staff", null, staffAuthorities()),
                new org.springframework.mock.web.MockHttpServletRequest());

        assertFalse((Boolean) model.getAttribute("canManageRoom"));
    }

    /**
     * Confirms the lookup with both dates is answered by the period-based booking availability, and that a
     * partial or reversed date range is rejected.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldAnswerDatedRoomLookupFromBookingAvailability() throws Exception {
        when(roomAvailability.bookableRoomsForPeriod(java.time.LocalDate.of(2026, 10, 10), java.time.LocalDate.of(2026, 10, 12)))
                .thenReturn(List.of(new RoomLookupResponse(ROOM_ID, "101", "OCCUPIED", true)));

        mockMvc.perform(get("/api/rooms/lookup").param("checkInDate", "2026-10-10").param("checkOutDate", "2026-10-12")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"roomNumber\":\"101\"")));
        mockMvc.perform(get("/api/rooms/lookup").param("checkInDate", "2026-10-10")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rooms/lookup").param("checkInDate", "2026-10-12").param("checkOutDate", "2026-10-12")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isBadRequest());
    }

    /**
     * Confirms the Reservation room lookup remains protected by MANAGE_BOOKING rather than MANAGE_ROOM.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldKeepReservationRoomLookupProtectedByManageBooking() throws Exception {
        when(roomQueryService.findAllForReservationCreation())
                .thenReturn(List.of(new RoomLookupResponse(ROOM_ID, "101", "AVAILABLE", true)));

        mockMvc.perform(get("/api/rooms/lookup")
                        .with(user("reservation-manager").authorities(manageBookingAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/rooms/lookup")
                        .with(user("room-manager").authorities(manageRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms Room Management does not expose REST deletion or RoomType write operations.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldNotExposeRoomOrRoomTypeWriteOperationsOutsideApprovedScope() throws Exception {
        mockMvc.perform(delete("/api/rooms/{id}", ROOM_ID)
                        .with(user("admin").authorities(manageRoomAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/room-types")
                        .with(user("admin").authorities(manageRoomAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * Confirms browser Room Management mutations retain Spring Security CSRF protection.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldRequireCsrfForRoomPageMutation() throws Exception {
        mockMvc.perform(post("/rooms")
                        .param("roomNumber", "101")
                        .param("roomTypeId", ROOM_TYPE_ID.toString())
                        .param("floor", "1")
                        .with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms Room creation opts into the shared normal confirmation convention. */
    @Test
    void shouldRenderNormalConfirmationMetadataForRoomCreation() throws Exception {
        when(roomTypeQueryService.findAll()).thenReturn(List.of(roomTypeResponse()));

        mockMvc.perform(get("/rooms/new").with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Create room\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-label=\"Create room\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-severity=\"NORMAL\"")));
    }

    /**
     * Confirms ADMIN and MANAGER can invoke every approved Room Operations B REST operation.
     *
     * @param username representative administrative user
     * @param operationPath approved Room Operations B path suffix
     * @throws Exception if MockMvc cannot perform the request
     */
    @ParameterizedTest
    @MethodSource("roomManagerOperations")
    void shouldAllowAdministrativeRolesToInvokeRoomOperations(String username, String operationPath)
            throws Exception {
        mockMvc.perform(post("/api/rooms/{id}/" + operationPath, ROOM_ID)
                        .with(user(username).authorities(manageRoomAuthority().get(0), manageHousekeepingAuthority().get(0)))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * Confirms STAFF cannot invoke any Room Operations B REST operation.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectStaffFromRoomOperations() throws Exception {
        for (String operationPath : roomOperationPaths().toList()) {
            mockMvc.perform(post("/api/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("staff").authorities(staffAuthorities()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Confirms MANAGE_HOUSEKEEPING alone authorizes both housekeeping transitions, over REST and MVC.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldAllowHousekeepingTransitionsWithoutManageRoom() throws Exception {
        for (String operationPath : List.of("start-cleaning", "finish-cleaning")) {
            mockMvc.perform(post("/api/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                            .with(csrf()))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
    }

    /**
     * Confirms MANAGE_ROOM alone no longer authorizes the housekeeping transitions.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRejectHousekeepingTransitionsWithOnlyManageRoom() throws Exception {
        for (String operationPath : List.of("start-cleaning", "finish-cleaning")) {
            mockMvc.perform(post("/api/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("room-admin").authorities(manageRoomAuthority()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("room-admin").authorities(manageRoomAuthority()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Confirms MANAGE_HOUSEKEEPING grants no room master-data, maintenance, out-of-order or read access.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldNotLetHousekeepingPermissionAdministerRooms() throws Exception {
        for (String operationPath : List.of(
                "start-maintenance", "finish-maintenance", "mark-out-of-order", "restore-to-service")) {
            mockMvc.perform(post("/api/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/rooms/{id}/" + operationPath, ROOM_ID)
                            .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/rooms")
                        .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"roomNumber\":\"101\",\"floor\":\"1\",\"roomTypeId\":\"" + ROOM_TYPE_ID + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/rooms/{id}", ROOM_ID)
                        .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"roomNumber\":\"101\",\"floor\":\"1\",\"roomTypeId\":\"" + ROOM_TYPE_ID + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/rooms/{id}", ROOM_ID)
                        .with(user("housekeeper").authorities(manageHousekeepingAuthority()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms/new").with(user("housekeeper").authorities(manageHousekeepingAuthority())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/rooms/{id}/edit", ROOM_ID)
                        .with(user("housekeeper").authorities(manageHousekeepingAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the housekeeping navigation flag follows MANAGE_HOUSEKEEPING only. */
    @Test
    void shouldExposeHousekeepingFlagOnlyWithManageHousekeeping() {
        NavigationModelAdvice advice = new NavigationModelAdvice();
        ExtendedModelMap with = new ExtendedModelMap();
        ExtendedModelMap without = new ExtendedModelMap();
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();

        advice.addNavigationAttributes(with, new UsernamePasswordAuthenticationToken(
                "housekeeper", null, manageHousekeepingAuthority()), request);
        advice.addNavigationAttributes(without, new UsernamePasswordAuthenticationToken(
                "room-admin", null, manageRoomAuthority()), request);

        org.junit.jupiter.api.Assertions.assertEquals(true, with.get("canManageHousekeeping"));
        org.junit.jupiter.api.Assertions.assertEquals(false, with.get("canManageRoom"));
        org.junit.jupiter.api.Assertions.assertEquals(false, without.get("canManageHousekeeping"));
    }

    /**
     * Confirms Room Operations B browser forms retain CSRF protection.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldRequireCsrfForRoomOperationPageMutation() throws Exception {
        mockMvc.perform(post("/rooms/{id}/start-maintenance", ROOM_ID)
                        .with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms no arbitrary status-update operation is exposed.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldNotExposeArbitraryRoomStatusUpdateEndpoint() throws Exception {
        mockMvc.perform(post("/api/rooms/{id}/status", ROOM_ID)
                        .with(user("admin").authorities(manageRoomAuthority()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * Supplies ADMIN and MANAGER as the roles that already hold MANAGE_ROOM.
     *
     * @return representative administrative usernames
     */
    private static Stream<Arguments> roomManagers() {
        return Stream.of(Arguments.of("admin"), Arguments.of("manager"));
    }

    /**
     * Supplies every administrative role and approved Room Operations B path combination.
     *
     * @return administrative user and operation-path combinations
     */
    private static Stream<Arguments> roomManagerOperations() {
        return Stream.of("admin", "manager")
                .flatMap(username -> roomOperationPaths().map(operationPath -> Arguments.of(username, operationPath)));
    }

    /**
     * Supplies the approved explicit Room Operations B path suffixes.
     *
     * @return operation path suffixes
     */
    private static Stream<String> roomOperationPaths() {
        return Stream.of(
                "start-cleaning",
                "finish-cleaning",
                "start-maintenance",
                "finish-maintenance",
                "mark-out-of-order",
                "restore-to-service");
    }

    /**
     * Builds the authority used by Room Management operations.
     *
     * @return the MANAGE_ROOM authority
     */
    private static List<SimpleGrantedAuthority> manageRoomAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_ROOM"));
    }

    /**
     * Builds the authority used by housekeeping transitions.
     *
     * @return the MANAGE_HOUSEKEEPING authority
     */
    private static List<SimpleGrantedAuthority> manageHousekeepingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_HOUSEKEEPING"));
    }

    /**
     * Builds the existing Reservation room-lookup authority.
     *
     * @return the MANAGE_BOOKING authority
     */
    private static List<SimpleGrantedAuthority> manageBookingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    /**
     * Builds the STAFF authority set without Room Management access.
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
     * Creates a representative Room response.
     *
     * @return a client-safe room response
     */
    private RoomResponse roomResponse() {
        return new RoomResponse(ROOM_ID, "101", roomTypeResponse(), "1", "AVAILABLE", true);
    }

    /**
     * Creates a representative read-only RoomType response.
     *
     * @return a RoomType response
     */
    private RoomTypeResponse roomTypeResponse() {
        return new RoomTypeResponse(ROOM_TYPE_ID, "SINGLE", "Single");
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Confirms an out-of-range Room page redirects to the last valid page preserving filter and sort. */
    @Test
    void shouldRedirectOutOfRangeRoomPagePreservingFilterAndSort() throws Exception {
        when(roomQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(), org.springframework.data.domain.PageRequest.of(50, 10), 31));

        mockMvc.perform(get("/rooms").param("page", "50").param("floor", "2").param("sort", "roomType").param("dir", "desc")
                        .with(user("admin").authorities(manageRoomAuthority())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/rooms?floor=2&sort=roomType&dir=desc&page=3"));
    }
}
