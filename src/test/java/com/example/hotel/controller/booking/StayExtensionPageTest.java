package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.StayExtensionFormResponse;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.StayExtensionService;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the Extend Stay MVC/REST routes: MANAGE_BOOKING authorization, CSRF, localized errors, no payment leak. */
@WebMvcTest({StayExtensionPageController.class, StayExtensionController.class})
@Import({StayExtensionPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class StayExtensionPageTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StayExtensionService service;

    @MockitoBean
    private JwtService jwtService;

    private static RequestPostProcessor manager() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    private StayExtensionFormResponse context(BigDecimal outstanding) {
        return new StayExtensionFormResponse(ID, "R20260920-000001", "Ann Lee", "G-1", "VND", LocalDate.of(2026, 9, 22),
                LocalDate.of(2026, 9, 23),
                List.of(new StayExtensionFormResponse.Line("201", "Single", new BigDecimal("1000000"))), outstanding);
    }

    /** Confirms a MANAGE_BOOKING user (ADMIN/MANAGER) reaches the form and sees the read-only context. */
    @Test
    void shouldShowTheFormToManageBooking() throws Exception {
        when(service.form(ID, false)).thenReturn(context(null));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("R20260920-000001")))
                .andExpect(content().string(containsString("name=\"expectedCurrentCheckOutDate\"")))
                .andExpect(content().string(containsString("name=\"newCheckOutDate\"")))
                .andExpect(content().string(not(containsString("name=\"nightlyRate\""))))
                .andExpect(content().string(not(containsString("outstanding balance"))));
    }

    /** Confirms STAFF (no MANAGE_BOOKING) and VIEW_BOOKING-only users are forbidden on every route. */
    @Test
    void shouldForbidUsersWithoutManageBooking() throws Exception {
        for (String authority : List.of("PERM_CHECK_IN", "PERM_VIEW_BOOKING", "PERM_MANAGE_PAYMENT")) {
            RequestPostProcessor other = user("u").authorities(new SimpleGrantedAuthority(authority));
            mockMvc.perform(get("/reservations/{id}/stay-extension", ID).with(other)).andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(other).with(csrf())
                            .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/reservations/{id}/stay-extension", ID).with(other).with(csrf())
                            .contentType("application/json")
                            .content("{\"expectedCurrentCheckOutDate\":\"2026-09-22\",\"newCheckOutDate\":\"2026-09-24\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).extend(any(), any());
    }

    /** Confirms the outstanding balance is shown only to a user who also holds MANAGE_PAYMENT. */
    @Test
    void shouldShowOutstandingOnlyWithManagePayment() throws Exception {
        when(service.form(ID, true)).thenReturn(context(new BigDecimal("2000000")));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).with(user("m").authorities(
                        new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"), new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"extension-outstanding\"")));
    }

    /** Confirms the POST requires CSRF, then redirects with a localized success message on success. */
    @Test
    void shouldRequireCsrfAndRedirectOnSuccess() throws Exception {
        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isForbidden());
        when(service.extend(eq(ID), any())).thenReturn(new Response(ID, "R1", "CHECKED_IN", BigDecimal.TEN, "VND"));

        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().is3xxRedirection());
    }

    /** Confirms a stale request re-renders the form with the localized stale-state message (EN and VI). */
    @Test
    void shouldShowLocalizedStaleError() throws Exception {
        when(service.form(ID, false)).thenReturn(context(null));
        when(service.extend(eq(ID), any())).thenThrow(new StayExtensionException(Reason.STALE_CHECK_OUT_DATE, "stale"));

        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-21").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The planned check-out changed since this form was opened")));
        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .cookie(new Cookie("pms-lang", "vi"))
                        .param("expectedCurrentCheckOutDate", "2026-09-21").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày trả phòng dự kiến đã thay đổi")));
    }

    /** Confirms an inventory conflict is localized with the room number and the form is redisplayed. */
    @Test
    void shouldShowLocalizedInventoryConflict() throws Exception {
        when(service.form(ID, false)).thenReturn(context(null));
        when(service.extend(eq(ID), any())).thenThrow(
                new StayExtensionException(Reason.INVENTORY_CONFLICT, "conflict", "201"));

        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room 201 is already booked or occupied")));
    }

    /** Confirms the REST route returns the structured status and maps a conflict to 409. */
    @Test
    void shouldExposeTheRestOperation() throws Exception {
        when(service.extend(eq(ID), any())).thenThrow(new StayExtensionException(Reason.INVENTORY_CONFLICT, "conflict", "201"));

        mockMvc.perform(post("/api/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCurrentCheckOutDate\":\"2026-09-22\",\"newCheckOutDate\":\"2026-09-24\"}"))
                .andExpect(status().isConflict());
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
