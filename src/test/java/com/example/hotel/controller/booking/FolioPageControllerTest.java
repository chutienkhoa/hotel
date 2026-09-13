package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
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

/** Verifies Folio MVC authorization, mutable rendering, and post-redirect-get behavior. */
@WebMvcTest(FolioPageController.class)
@Import(FolioPageControllerTest.MethodSecurityTestConfiguration.class)
class FolioPageControllerTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STAY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private ChargeService chargeService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms a payment manager can view a checked-in Folio with all approved mutable controls. */
    @Test
    void shouldRenderMutableFolioForManagePayment() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(view().name("stay/folio"))
                .andExpect(content().string(containsString("Total Charges")))
                .andExpect(content().string(containsString("Add Charge")))
                .andExpect(content().string(containsString("Add Payment")))
                .andExpect(content().string(containsString("Mark paid")))
                .andExpect(content().string(containsString("ROOM")))
                .andExpect(content().string(containsString("0 VND")));
    }

    /** Confirms a closed Folio renders its history but no financial mutation controls. */
    @Test
    void shouldRenderCheckedOutFolioAsReadOnly() throws Exception {
        stubFolio("CHECKED_OUT", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This Folio is closed and read-only.")))
                .andExpect(content().string(containsString("Total Paid Payments")))
                .andExpect(content().string(not(containsString("Add Charge"))))
                .andExpect(content().string(not(containsString("Add Payment"))))
                .andExpect(content().string(not(containsString("Refund"))));
    }

    /** Confirms CHECK_OUT alone cannot access detailed Folio financial information. */
    @Test
    void shouldRejectCheckOutOnlyUserFromDetailedFolio() throws Exception {
        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("staff").authorities(checkOut())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a valid Charge form is CSRF-protected and uses PRG on success. */
    @Test
    void shouldCreateChargeWithCsrfAndRedirectToFolio() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "ROOM")
                        .param("amount", "100.00")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID + "/folio"));
    }

    /** Confirms Folio financial POST routes reject browser submissions without a CSRF token. */
    @Test
    void shouldRequireCsrfForChargeCreation() throws Exception {
        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "ROOM")
                        .param("amount", "100.00")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isForbidden());
    }

    /** Supplies the complete service-backed model required to render a Folio. */
    private void stubFolio(String stayStatus, BigDecimal outstanding, String paymentStatus) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation(stayStatus));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay(stayStatus));
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge()));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment(paymentStatus)));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100.00"),
                new BigDecimal("100.00").subtract(outstanding),
                outstanding));
    }

    /** Creates the Reservation context shown by the Folio. */
    private ReservationDetailResponse reservation(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                status,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                BigDecimal.TEN,
                "VND",
                null,
                List.of());
    }

    /** Creates the Stay context shown by the Folio. */
    private StayResponse stay(String status) {
        return new StayResponse(
                STAY_ID,
                status,
                Instant.parse("2026-09-11T10:00:00Z"),
                "CHECKED_OUT".equals(status) ? Instant.parse("2026-09-12T10:00:00Z") : null);
    }

    /** Creates a representative Charge entry. */
    private ChargeResponse charge() {
        return new ChargeResponse(
                UUID.randomUUID(),
                STAY_ID,
                "ROOM",
                "Room charge",
                null,
                null,
                new BigDecimal("100.00"),
                Instant.parse("2026-09-11T10:00:00Z"));
    }

    /** Creates a representative Payment entry. */
    private PaymentResponse payment(String status) {
        return new PaymentResponse(
                UUID.randomUUID(),
                STAY_ID,
                new BigDecimal("100.00"),
                "CASH",
                status,
                "PAID".equals(status) ? Instant.parse("2026-09-11T11:00:00Z") : null,
                null);
    }

    /** Builds the approved Folio financial authority. */
    private SimpleGrantedAuthority managePayment() {
        return new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT");
    }

    /** Builds the check-out authority that must not grant detailed Folio access. */
    private SimpleGrantedAuthority checkOut() {
        return new SimpleGrantedAuthority("PERM_CHECK_OUT");
    }

    /** Enables method-security interception for this MVC test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
