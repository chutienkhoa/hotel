package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies MANAGE_PAYMENT protects all Payment v1 REST operations. */
@WebMvcTest(PaymentController.class)
@Import(PaymentAuthorizationTest.MethodSecurityTestConfiguration.class)
class PaymentAuthorizationTest {

    private static final UUID STAY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms MANAGE_PAYMENT can create, list, and perform every approved state operation. */
    @Test
    void shouldAllowManagePaymentForAllPaymentV1Operations() throws Exception {
        when(paymentService.create(eq(STAY_ID), any())).thenReturn(response("PENDING"));
        when(paymentService.recordPaid(eq(STAY_ID), any())).thenReturn(response("PAID"));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(response("PENDING")));
        when(paymentService.markPaid(PAYMENT_ID)).thenReturn(response("PAID"));
        when(paymentService.markFailed(PAYMENT_ID)).thenReturn(response("FAILED"));
        when(paymentService.refund(eq(PAYMENT_ID), any())).thenReturn(response("REFUNDED"));

        mockMvc.perform(post("/api/stays/{stayId}/payments", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"currency\":\"VND\",\"method\":\"CASH\"}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stays/{stayId}/payments/record-paid", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"currency\":\"VND\",\"method\":\"CASH\"}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stays/{stayId}/payments", STAY_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk());
        for (String operation : List.of("mark-paid", "mark-failed")) {
            mockMvc.perform(post("/api/payments/{id}/" + operation, PAYMENT_ID)
                            .with(user("manager").authorities(managePayment()))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/payments/{id}/refund", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Guest cancelled\"}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /** Confirms MANAGE_PAYMENT can also perform the refund operation, protected the same as the rest. */
    @Test
    void shouldRequireManagePaymentForRefund() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/refund", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Guest cancelled\"}")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(paymentService, org.mockito.Mockito.never()).refund(any(), any());
    }

    /** Confirms a refund request without a reason is rejected before reaching the service. */
    @Test
    void shouldRejectRefundWithoutReasonAtRestBoundary() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/refund", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Confirms users without MANAGE_PAYMENT cannot create a Payment. */
    @Test
    void shouldRejectUnauthorizedPaymentCreation() throws Exception {
        mockMvc.perform(post("/api/stays/{stayId}/payments", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"currency\":\"VND\",\"method\":\"CASH\"}")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms client JSON cannot override server-controlled Payment status or paid time. */
    @Test
    void shouldIgnoreClientSuppliedPaymentStatusAndPaidAt() throws Exception {
        when(paymentService.create(eq(STAY_ID), any())).thenReturn(response("PENDING"));

        mockMvc.perform(post("/api/stays/{stayId}/payments", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"currency\":\"VND\",\"method\":\"CASH\",\"status\":\"PAID\","
                                + "\"paidAt\":\"2026-09-11T00:00:00Z\"}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paidAt").value(nullValue()));
    }

    /** Confirms MANAGE_PAYMENT authorizes voiding a Payment, protected the same as the rest. */
    @Test
    void shouldAllowManagePaymentToVoidPayment() throws Exception {
        when(paymentService.voidPayment(eq(PAYMENT_ID), any())).thenReturn(response("VOIDED"));

        mockMvc.perform(post("/api/payments/{id}/void", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate entry\"}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /** Confirms users without MANAGE_PAYMENT cannot void a Payment. */
    @Test
    void shouldRequireManagePaymentForVoid() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/void", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate entry\"}")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(paymentService, org.mockito.Mockito.never()).voidPayment(any(), any());
    }

    /** Confirms a void request without a reason is rejected before reaching the service. */
    @Test
    void shouldRejectVoidWithoutReasonAtRestBoundary() throws Exception {
        mockMvc.perform(post("/api/payments/{id}/void", PAYMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Confirms generic Payment update and delete endpoints do not exist. */
    @Test
    void shouldNotExposeGenericPaymentUpdateOrDelete() throws Exception {
        mockMvc.perform(put("/api/payments/{id}", PAYMENT_ID)
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/payments/{id}", PAYMENT_ID)
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** Builds the existing payment-management authority. */
    private static List<SimpleGrantedAuthority> managePayment() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    /** Builds the existing STAFF authority set without payment management. */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /** Creates a Payment response fixture. */
    private PaymentResponse response(String status) {
        return new PaymentResponse(
                PAYMENT_ID,
                STAY_ID,
                BigDecimal.TEN,
                "VND",
                null,
                BigDecimal.TEN,
                "CASH",
                status,
                null,
                null,
                null,
                null);
    }

    /** Enables method-security interception for the controller test. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
