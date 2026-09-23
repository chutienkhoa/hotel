package com.example.hotel.controller.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import java.math.BigDecimal;
import java.time.Instant;
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

/** Verifies the MANAGE_PAYMENT authorization boundary for Charge v1 REST operations. */
@WebMvcTest(ChargeController.class)
@Import(ChargeAuthorizationTest.MethodSecurityTestConfiguration.class)
class ChargeAuthorizationTest {

    private static final UUID STAY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChargeService chargeService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms MANAGE_PAYMENT authorizes Charge creation and Stay-scoped listing. */
    @Test
    void shouldAllowManagePaymentToCreateAndListCharges() throws Exception {
        when(chargeService.create(eq(STAY_ID), any())).thenReturn(response());
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(response()));

        mockMvc.perform(post("/api/stays/{stayId}/charges", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ROOM\",\"amount\":100}")
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stays/{stayId}/charges", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BREAKFAST\",\"quantity\":2,\"unitPrice\":150000}")
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stays/{stayId}/charges", STAY_ID)
                .with(user("manager").authorities(managePaymentAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms REST validation rejects a client amount combined with itemized pricing inputs. */
    @Test
    void shouldRejectClientAmountForItemizedChargeRequest() throws Exception {
        mockMvc.perform(post("/api/stays/{stayId}/charges", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BREAKFAST\",\"quantity\":2,\"unitPrice\":150000,\"amount\":1}")
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Confirms users without MANAGE_PAYMENT cannot create or list Charges. */
    @Test
    void shouldRejectUnauthorizedRolesFromChargeOperations() throws Exception {
        mockMvc.perform(post("/api/stays/{stayId}/charges", STAY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ROOM\",\"amount\":100}")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stays/{stayId}/charges", STAY_ID)
                        .with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    /** Confirms MANAGE_PAYMENT authorizes voiding a Charge. */
    @Test
    void shouldAllowManagePaymentToVoidCharge() throws Exception {
        UUID chargeId = UUID.randomUUID();
        when(chargeService.voidCharge(eq(chargeId), any())).thenReturn(response());

        mockMvc.perform(post("/api/stays/{stayId}/charges/{chargeId}/void", STAY_ID, chargeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Entered by mistake\"}")
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /** Confirms users without MANAGE_PAYMENT cannot void a Charge. */
    @Test
    void shouldRejectUnauthorizedRoleFromVoidingCharge() throws Exception {
        UUID chargeId = UUID.randomUUID();

        mockMvc.perform(post("/api/stays/{stayId}/charges/{chargeId}/void", STAY_ID, chargeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Entered by mistake\"}")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms a void request without a reason is rejected before reaching the service. */
    @Test
    void shouldRejectVoidWithoutReasonAtRestBoundary() throws Exception {
        UUID chargeId = UUID.randomUUID();

        mockMvc.perform(post("/api/stays/{stayId}/charges/{chargeId}/void", STAY_ID, chargeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Confirms Charge v1 exposes neither update nor deletion REST operations. */
    @Test
    void shouldNotExposeChargeUpdateOrDeleteOperations() throws Exception {
        mockMvc.perform(put("/api/stays/{stayId}/charges/{id}", STAY_ID, UUID.randomUUID())
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/stays/{stayId}/charges/{id}", STAY_ID, UUID.randomUUID())
                        .with(user("manager").authorities(managePaymentAuthority()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * Builds the existing payment-management authority used by ADMIN and MANAGER.
     *
     * @return MANAGE_PAYMENT authority
     */
    private static List<SimpleGrantedAuthority> managePaymentAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    /**
     * Builds the existing STAFF authorities, which intentionally omit MANAGE_PAYMENT.
     *
     * @return staff authority set
     */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /**
     * Creates a representative Charge response for controller authorization tests.
     *
     * @return Charge response fixture
     */
    private ChargeResponse response() {
        return new ChargeResponse(
                UUID.randomUUID(),
                STAY_ID,
                "ROOM",
                null,
                null,
                null,
                BigDecimal.TEN,
                Instant.now(),
                "ACTIVE",
                null);
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
