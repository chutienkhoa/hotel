package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
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
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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

/** Verifies the Prepayment MVC/REST routes: MANAGE_PAYMENT only, CSRF, localized rejections, safe-reference hint. */
@WebMvcTest({PrepaymentPageController.class, PrepaymentController.class})
@Import({PrepaymentPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class PrepaymentPageTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrepaymentService service;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private JwtService jwtService;

    private static RequestPostProcessor payer() {
        return user("cashier").authorities(new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    private void reservation() {
        when(reservationQueryService.findById(ID)).thenReturn(new ReservationDetailResponse(
                ID, "R20260920-000001", UUID.randomUUID(), "G-1", "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.DIRECT, null,
                java.time.LocalDate.of(2026, 9, 20), java.time.LocalDate.of(2026, 9, 22), 1, 0,
                new BigDecimal("4000000"), "VND", null, List.of(), List.of()));
    }

    /** Confirms a MANAGE_PAYMENT user gets the form with the credential-safety hint and no card fields. */
    @Test
    void shouldShowTheFormWithTheSafeReferenceHint() throws Exception {
        reservation();

        mockMvc.perform(get("/reservations/{id}/prepayments", ID).with(payer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"reference\"")))
                .andExpect(content().string(containsString("Never enter card numbers, CVV or bank credentials")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(containsString("cardNumber"))));
    }

    /** Confirms users without MANAGE_PAYMENT (including CHECK_IN and VIEW_BOOKING) are forbidden everywhere. */
    @Test
    void shouldForbidUsersWithoutManagePayment() throws Exception {
        for (String authority : List.of("PERM_CHECK_IN", "PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING")) {
            RequestPostProcessor other = user("u").authorities(new SimpleGrantedAuthority(authority));
            mockMvc.perform(get("/reservations/{id}/prepayments", ID).with(other)).andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/prepayments", ID).with(other).with(csrf())
                    .param("amount", "1").param("currency", "VND").param("method", "CASH")).andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/prepayments/{p}/refund", ID, ID).with(other).with(csrf())
                    .param("reason", "x")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/reservations/{id}/prepayments", ID).with(other)).andExpect(status().isForbidden());
        }
        verify(service, never()).record(any(), any());
    }

    /** Confirms CSRF is required and a valid post redirects. */
    @Test
    void shouldRequireCsrfAndRedirectOnSuccess() throws Exception {
        mockMvc.perform(post("/reservations/{id}/prepayments", ID).with(payer())
                .param("amount", "1").param("currency", "VND").param("method", "CASH")).andExpect(status().isForbidden());

        mockMvc.perform(post("/reservations/{id}/prepayments", ID).with(payer()).with(csrf())
                .param("amount", "1000").param("currency", "VND").param("method", "CASH")).andExpect(status().is3xxRedirection());
    }

    /** Confirms a rejection is shown with the localized message in EN and VI. */
    @Test
    void shouldShowLocalizedOverpaymentRejection() throws Exception {
        reservation();
        when(service.record(eq(ID), any())).thenThrow(new LocalizedResponseStatusException(
                HttpStatus.CONFLICT, "payment.prepayment.error.overpayment", "over"));

        mockMvc.perform(post("/reservations/{id}/prepayments", ID).with(payer()).with(csrf())
                        .param("amount", "9").param("currency", "VND").param("method", "CASH"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Prepayments would exceed the reservation total")));
        mockMvc.perform(post("/reservations/{id}/prepayments", ID).with(payer()).with(csrf())
                        .cookie(new Cookie("pms-lang", "vi"))
                        .param("amount", "9").param("currency", "VND").param("method", "CASH"))
                .andExpect(content().string(containsString("vượt quá tổng tiền đặt phòng")));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
