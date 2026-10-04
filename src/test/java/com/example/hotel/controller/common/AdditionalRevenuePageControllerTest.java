package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.dto.common.response.AdditionalRevenueResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.AdditionalRevenueService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Additional Revenue MVC authorization, lifecycle presentation, and shared form conventions. */
@WebMvcTest(AdditionalRevenuePageController.class)
@Import(AdditionalRevenuePageControllerTest.MethodSecurityTestConfiguration.class)
class AdditionalRevenuePageControllerTest {

    private static final UUID REVENUE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdditionalRevenueService additionalRevenueService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void shouldAllowAdditionalRevenueManagersAndRejectStaff() throws Exception {
        stubRevenuePage(recorded());

        mockMvc.perform(get("/additional-revenues").with(user("manager").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Additional Revenue")));
        mockMvc.perform(get("/additional-revenues").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRenderFiltersPreserveThemInPaginationAndRejectInvalidRanges() throws Exception {
        when(additionalRevenueService.findAllCategories()).thenReturn(List.of(category(true), category(false)));
        when(additionalRevenueService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(recorded()), PageRequest.of(0, 20), 21));

        mockMvc.perform(get("/additional-revenues")
                        .param("fromDate", "2026-09-01")
                        .param("toDate", "2026-09-30")
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("status", "VOIDED")
                        .with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"js-date-picker\" id=\"fromDate\"")))
                .andExpect(content().string(containsString("status=VOIDED")))
                .andExpect(content().string(containsString("href=\"/additional-revenues\">Clear</a>")));

        org.mockito.Mockito.clearInvocations(additionalRevenueService);
        when(additionalRevenueService.findAllCategories()).thenReturn(List.of(category(true), category(false)));
        mockMvc.perform(get("/additional-revenues")
                        .param("fromDate", "2026-09-30")
                        .param("toDate", "2026-09-01")
                        .with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("From Date must not be after To Date.")));
        org.mockito.Mockito.verify(additionalRevenueService, org.mockito.Mockito.never())
                .findPage(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldRenderCreateFormWithSharedMoneyAndDatePickerConventions() throws Exception {
        when(additionalRevenueService.findActiveCategories()).thenReturn(List.of(category(true)));

        mockMvc.perform(get("/additional-revenues/new").with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-numeric=\"vnd\" id=\"amount\"")))
                .andExpect(content().string(containsString("class=\"js-date-picker\" id=\"revenueDate\"")))
                .andExpect(content().string(containsString("Electric Cart Rental")));
    }

    @Test
    void shouldHideEditAndVoidActionsForVoidedRevenue() throws Exception {
        when(additionalRevenueService.findById(REVENUE_ID)).thenReturn(voided());

        mockMvc.perform(get("/additional-revenues/{id}", REVENUE_ID)
                        .with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("VOIDED")))
                .andExpect(content().string(containsString("Incorrect duplicate entry")))
                .andExpect(content().string(not(containsString("Edit Revenue"))))
                .andExpect(content().string(not(containsString("Void Revenue"))));
    }

    @Test
    void shouldRenderRecordedVoidFormAndFriendlyPaymentMethod() throws Exception {
        when(additionalRevenueService.findById(REVENUE_ID)).thenReturn(recorded("BANK_TRANSFER"));

        mockMvc.perform(get("/additional-revenues/{id}", REVENUE_ID)
                        .with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Voiding this revenue will exclude it from revenue reporting.")))
                .andExpect(content().string(containsString("id=\"voidReason\"")))
                .andExpect(content().string(containsString("Void Revenue")))
                .andExpect(content().string(containsString("Bank Transfer")))
                .andExpect(content().string(not(containsString("BANK_TRANSFER"))));
    }

    @Test
    void shouldRenderFriendlyPaymentMethodInList() throws Exception {
        stubRevenuePage(recorded("CREDIT_CARD"));

        mockMvc.perform(get("/additional-revenues").with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Credit Card")))
                .andExpect(content().string(not(containsString("CREDIT_CARD"))));
    }

    @Test
    void shouldRequireCsrfForVoidPost() throws Exception {
        mockMvc.perform(post("/additional-revenues/{id}/void", REVENUE_ID)
                        .param("voidReason", "Incorrect duplicate entry")
                        .with(user("admin").authorities(manageAdditionalRevenue())))
                .andExpect(status().isForbidden());

        when(additionalRevenueService.voidRevenue(any(), any())).thenReturn(voided());
        mockMvc.perform(post("/additional-revenues/{id}/void", REVENUE_ID)
                        .param("voidReason", "Incorrect duplicate entry")
                        .with(user("admin").authorities(manageAdditionalRevenue()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private static List<SimpleGrantedAuthority> manageAdditionalRevenue() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_ADDITIONAL_REVENUE"));
    }

    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    private static AdditionalRevenueCategoryResponse category(boolean active) {
        return new AdditionalRevenueCategoryResponse(CATEGORY_ID, "ELECTRIC_CART_RENTAL", "Electric Cart Rental", null, active);
    }

    private static AdditionalRevenueResponse recorded() {
        return recorded("CASH");
    }

    private static AdditionalRevenueResponse recorded(String paymentMethod) {
        return new AdditionalRevenueResponse(
                REVENUE_ID, category(true), new BigDecimal("150000"), "VND", LocalDate.of(2026, 9, 15),
                paymentMethod, null, "RECORDED", null, null, null, false);
    }

    private static AdditionalRevenueResponse voided() {
        return new AdditionalRevenueResponse(
                REVENUE_ID, category(false), new BigDecimal("150000"), "VND", LocalDate.of(2026, 9, 15),
                "CASH", null, "VOIDED", "Incorrect duplicate entry", Instant.parse("2026-09-15T10:00:00Z"), UUID.randomUUID(), false);
    }

    private void stubRevenuePage(AdditionalRevenueResponse... revenues) {
        when(additionalRevenueService.findAllCategories()).thenReturn(List.of(category(true), category(false)));
        when(additionalRevenueService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(revenues), PageRequest.of(0, 20), revenues.length));
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
