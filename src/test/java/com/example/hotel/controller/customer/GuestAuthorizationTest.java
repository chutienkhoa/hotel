package com.example.hotel.controller.customer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.customer.response.GuestListResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the approved authorization boundary between Guest Management and reservation lookup. */
@WebMvcTest({GuestController.class, GuestLookupController.class, GuestPageController.class})
@Import({GuestAuthorizationTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class})
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

    /** Confirms the shared Guest form grid is used for both creation and editing. */
    @Test
    void shouldRenderGuestFormGridForCreationAndEditing() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-grid guest-form-grid\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-field guest-form-address\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"js-date-picker\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Create guest\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-label=\"Create guest\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-severity=\"NORMAL\"")));

        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse());
        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "class=\"form-grid guest-form-grid\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Update guest\"")));
    }

    /** Confirms the Create form renders Nationality as a placeholder-led country select, not free text. */
    @Test
    void shouldRenderNationalityAsCountrySelectOnCreateForm() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"nationality\" name=\"nationality\">")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "id=\"nationality\" maxlength=\"100\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<option value=\"\">Select nationality</option>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇯🇵 Japan")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳 Vietnam")));
    }

    /** Confirms the Edit form preselects the Guest's existing canonical nationality. */
    @Test
    void shouldPreselectCanonicalNationalityOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Japan"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")));
    }

    /** Confirms a known legacy nationality safely preselects its canonical country on the Edit form. */
    @Test
    void shouldMapKnownLegacyNationalityToCanonicalSelectionOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Japanese"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        ">Japanese</option>"))));
    }

    /** Confirms an unmappable legacy nationality is preserved as a selected option, not silently discarded. */
    @Test
    void shouldPreserveUnknownLegacyNationalityOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guestResponse("Atlantean"));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID)
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Atlantean\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Atlantean</option>")));
    }

    /** Confirms creating a guest persists the canonical country name selected from the dropdown. */
    @Test
    void shouldSubmitCanonicalCountryNameWhenCreatingGuest() throws Exception {
        when(guestService.create(org.mockito.ArgumentMatchers.any())).thenReturn(guestResponse("Japan"));
        org.mockito.ArgumentCaptor<com.example.hotel.dto.customer.request.GuestCreateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.example.hotel.dto.customer.request.GuestCreateRequest.class);

        mockMvc.perform(post("/guests")
                        .param("firstName", "Khoa")
                        .param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(guestService).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("Japan", captor.getValue().nationality());
    }

    /** Confirms the Guest list renders five independent filter fields and no generic Search field. */
    @Test
    void shouldRenderFiveIndependentGuestFilterFieldsWithoutGenericSearchField() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"guestCode\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"firstName\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"lastName\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"nationality\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"query\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">Search</label>"))));
    }

    /** Confirms the Guest list preserves every active filter while using shared result and pagination markup. */
    @Test
    void shouldRenderFilteredPaginatedGuestListPreservingAllFilters() throws Exception {
        GuestListResponse guest = new GuestListResponse(
                GUEST_ID,
                "G000001",
                "Khoa",
                "Chu",
                "khoa@example.com",
                new com.example.hotel.dto.customer.response.GuestNationalityDisplay("Vietnam", "🇻🇳"));
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(guest), PageRequest.of(0, 10), 11));

        mockMvc.perform(get("/guests")
                        .param("firstName", "Khoa")
                        .param("nationality", "Vietnam")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"firstName\" name=\"firstName\" type=\"search\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Khoa\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Vietnam\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"pagination guest-pagination\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pagination__segment--current")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/guests?firstName=Khoa&amp;nationality=Vietnam&amp;page=1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("G000001")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/guests/" + GUEST_ID + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vietnam")));
    }

    /** Confirms an empty Guest filter result keeps the selected nationality and omits stale table and pagination content. */
    @Test
    void shouldRenderZeroResultGuestFilterWithoutPagination() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").param("nationality", "Japan")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0</span>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "No guests match the current filters.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"Japan\" selected=\"selected\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "class=\"pagination guest-pagination\""))));
    }

    /** Confirms the Nationality filter is a country-selection dropdown, not a free-text input. */
    @Test
    void shouldRenderNationalityFilterAsCountryDropdown() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<select id=\"nationality\" name=\"nationality\">")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "id=\"nationality\" name=\"nationality\" type=\"search\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All nationalities")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇯🇵 Japan")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("🇻🇳 Vietnam")));
    }

    /** Confirms Reset always points to the unfiltered Guest list regardless of active filters. */
    @Test
    void shouldPointResetToUnfilteredGuestList() throws Exception {
        when(guestQueryService.findPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").param("firstName", "Khoa")
                        .with(user("admin").authorities(manageGuestAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/guests\">Reset</a>")));
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
        return guestResponse("Japan");
    }

    /**
     * Creates a representative client-safe guest profile response with the given nationality.
     *
     * @param nationality stored nationality text to use for the response
     * @return a guest response for controller testing
     */
    private GuestResponse guestResponse(String nationality) {
        return new GuestResponse(
                GUEST_ID,
                "G000001",
                "First",
                "Last",
                "guest@example.com",
                "0123456789",
                nationality,
                null,
                "Tokyo");
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
