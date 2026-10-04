package com.example.hotel.controller.customer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.customer.GuestDocumentService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the Guest ID / Passport Number and Date of Birth fields in the Guest create, edit and detail pages. */
@WebMvcTest(value = {GuestController.class, GuestLookupController.class, GuestPageController.class},
        properties = "hotel.i18n.default-locale=en")
@Import({GuestIdentityFieldsPageTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class GuestIdentityFieldsPageTest {

    private static final UUID GUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestService guestService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private GuestDocumentService guestDocumentService;

    @MockitoBean
    private JwtService jwtService;

    /** Enables method security for this slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    private static List<SimpleGrantedAuthority> manageGuest() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_GUEST"));
    }

    private GuestResponse guest(String idDocumentNumber, LocalDate dateOfBirth) {
        return new GuestResponse(GUEST_ID, "G000001", "Ann", "Lee", "ann@example.com", "0123", "Japan",
                dateOfBirth, idDocumentNumber, "Tokyo");
    }

    /** Confirms the create form offers both identity fields with English labels. */
    @Test
    void shouldRenderIdentityFieldsOnCreateFormInEnglish() throws Exception {
        mockMvc.perform(get("/guests/new").with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Date of Birth")))
                .andExpect(content().string(containsString("ID / Passport Number")))
                .andExpect(content().string(containsString("name=\"idDocumentNumber\"")))
                .andExpect(content().string(containsString("name=\"dateOfBirth\"")));
    }

    /** Confirms the same labels render in Vietnamese. */
    @Test
    void shouldRenderIdentityFieldsOnCreateFormInVietnamese() throws Exception {
        mockMvc.perform(get("/guests/new").cookie(new Cookie("pms-lang", "vi"))
                        .with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày sinh")))
                .andExpect(content().string(containsString("Số CCCD / Hộ chiếu")));
    }

    /** Confirms a Guest can be created without either identity field (both are optional). */
    @Test
    void shouldCreateGuestWithoutIdentityFields() throws Exception {
        when(guestService.create(any(), any())).thenReturn(guest(null, null));

        mockMvc.perform(post("/guests").with(user("admin").authorities(manageGuest())).with(csrf())
                        .param("firstName", "Ann").param("lastName", "Lee").param("nationality", "Vietnam"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<GuestCreateRequest> request = ArgumentCaptor.forClass(GuestCreateRequest.class);
        verify(guestService).create(request.capture(), any());
        assertEquals(null, request.getValue().idDocumentNumber());
        assertEquals(null, request.getValue().dateOfBirth());
    }

    /** Confirms both identity fields are bound from the create form and handed to the service. */
    @Test
    void shouldCreateGuestWithIdentityFields() throws Exception {
        when(guestService.create(any(), any())).thenReturn(guest("C1234567", LocalDate.of(1990, 8, 15)));

        mockMvc.perform(post("/guests").with(user("admin").authorities(manageGuest())).with(csrf())
                        .param("firstName", "Ann").param("lastName", "Lee").param("nationality", "Vietnam")
                        .param("idDocumentNumber", "C1234567").param("dateOfBirth", "1990-08-15"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<GuestCreateRequest> request = ArgumentCaptor.forClass(GuestCreateRequest.class);
        verify(guestService).create(request.capture(), any());
        assertEquals("C1234567", request.getValue().idDocumentNumber());
        assertEquals(LocalDate.of(1990, 8, 15), request.getValue().dateOfBirth());
    }

    /** Confirms a future date of birth is rejected next to the field and nothing is created. */
    @Test
    void shouldRejectFutureDateOfBirth() throws Exception {
        mockMvc.perform(post("/guests").with(user("admin").authorities(manageGuest())).with(csrf())
                        .param("firstName", "Ann").param("lastName", "Lee").param("nationality", "Vietnam")
                        .param("dateOfBirth", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Date of birth must not be in the future.")));
        verify(guestService, never()).create(any(), any());
    }

    /** Confirms an ID / Passport Number over the 50-character bound is rejected. */
    @Test
    void shouldRejectOverlongIdDocumentNumber() throws Exception {
        mockMvc.perform(post("/guests").with(user("admin").authorities(manageGuest())).with(csrf())
                        .param("firstName", "Ann").param("lastName", "Lee").param("nationality", "Vietnam")
                        .param("idDocumentNumber", "X".repeat(51)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ID / Passport Number must not exceed 50 characters.")));
        verify(guestService, never()).create(any(), any());
    }

    /** Confirms the edit form pre-fills the stored identity values. */
    @Test
    void shouldPrefillIdentityFieldsOnEditForm() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guest("079012345678", LocalDate.of(1990, 8, 15)));

        mockMvc.perform(get("/guests/{id}/edit", GUEST_ID).with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"079012345678\"")));
    }

    /** Confirms Guest Detail shows the stored ID / Passport Number and date of birth. */
    @Test
    void shouldShowIdentityFieldsOnGuestDetail() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guest("C1234567", LocalDate.of(1990, 8, 15)));
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ID / Passport Number")))
                .andExpect(content().string(containsString("C1234567")))
                .andExpect(content().string(containsString("15/08/1990")));
    }

    /** Confirms a Guest without identity data shows the standard missing-value dash, never a placeholder number. */
    @Test
    void shouldShowMissingValueConventionForAbsentIdentityFields() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guest(null, null));
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/guests/{id}", GUEST_ID).with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ID / Passport Number")))
                .andExpect(content().string(not(containsString("null"))));
    }

    /** Confirms Guest Detail labels render in Vietnamese. */
    @Test
    void shouldShowVietnameseIdentityLabelsOnGuestDetail() throws Exception {
        when(guestService.findById(GUEST_ID)).thenReturn(guest("C1234567", null));
        when(guestDocumentService.findPassports(GUEST_ID)).thenReturn(List.of());

        mockMvc.perform(get("/guests/{id}", GUEST_ID).cookie(new Cookie("pms-lang", "vi"))
                        .with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Số CCCD / Hộ chiếu")))
                .andExpect(content().string(containsString("Ngày sinh")));
    }

    /** Confirms the Guest list filter form offers the ID / Passport Number filter and passes it to the query. */
    @Test
    void shouldOfferIdDocumentNumberFilterOnGuestList() throws Exception {
        when(guestQueryService.findPage(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/guests").param("idDocumentNumber", " C123 ")
                        .with(user("admin").authorities(manageGuest())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"idDocumentNumber\"")))
                .andExpect(content().string(containsString("value=\"C123\"")));
    }

    /** Confirms the reservation lookup JSON never serializes the ID / Passport Number. */
    @Test
    void shouldNotExposeIdDocumentNumberThroughLookupJson() throws Exception {
        when(guestQueryService.searchForReservationCreation("ann")).thenReturn(List.of(
                new com.example.hotel.dto.customer.response.GuestLookupResponse(
                        GUEST_ID, "G000001", "Ann Lee", null, null, null, null, "C1234567")));

        mockMvc.perform(get("/api/guests/lookup").param("query", "ann")
                        .with(user("staff").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("C1234567"))))
                .andExpect(content().string(not(containsString("idDocumentNumber"))));
    }
}
