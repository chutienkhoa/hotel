package com.example.hotel.controller.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.ExpenseService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;

/** Verifies Expense v1 REST and MVC authorization, server-owned fields, and CSRF boundaries. */
@WebMvcTest({ExpenseController.class, ExpensePageController.class})
@Import(ExpenseAuthorizationTest.MethodSecurityTestConfiguration.class)
class ExpenseAuthorizationTest {

    private static final UUID EXPENSE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseService expenseService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms MANAGE_EXPENSE grants every approved Expense v1 REST operation. */
    @Test
    void shouldAllowManageExpenseForAllRestOperations() throws Exception {
        when(expenseService.findAll()).thenReturn(List.of(response("DRAFT")));
        when(expenseService.findById(EXPENSE_ID)).thenReturn(response("DRAFT"));
        when(expenseService.create(any())).thenReturn(response("DRAFT"));
        when(expenseService.update(eq(EXPENSE_ID), any())).thenReturn(response("DRAFT"));
        when(expenseService.submit(EXPENSE_ID)).thenReturn(response("SUBMITTED"));
        when(expenseService.approve(EXPENSE_ID)).thenReturn(response("APPROVED"));
        when(expenseService.reject(EXPENSE_ID)).thenReturn(response("REJECTED"));
        when(expenseService.post(EXPENSE_ID)).thenReturn(response("POSTED"));

        mockMvc.perform(get("/api/expenses").with(user("admin").authorities(manageExpenseAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expenses/{id}", EXPENSE_ID)
                        .with(user("admin").authorities(manageExpenseAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/expenses/{id}", EXPENSE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        for (String operation : List.of("submit", "approve", "reject", "post")) {
            mockMvc.perform(post("/api/expenses/{id}/" + operation, EXPENSE_ID)
                            .with(user("admin").authorities(manageExpenseAuthority()))
                            .with(csrf()))
                    .andExpect(status().isOk());
        }
    }

    /** Confirms STAFF, which does not hold MANAGE_EXPENSE, cannot access Expense v1 operations. */
    @Test
    void shouldRejectRolesWithoutManageExpense() throws Exception {
        mockMvc.perform(get("/api/expenses").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms MANAGER, which now holds MANAGE_EXPENSE, can perform Expense v1 REST and MVC operations. */
    @Test
    void shouldAllowManagerToAccessExpenseOperations() throws Exception {
        when(expenseService.findAll()).thenReturn(List.of(response("DRAFT")));
        when(expenseService.findPage(any(), eq(0))).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(response("DRAFT"))));
        when(expenseService.create(any())).thenReturn(response("DRAFT"));
        when(expenseService.submit(EXPENSE_ID)).thenReturn(response("SUBMITTED"));

        mockMvc.perform(get("/api/expenses").with(user("manager").authorities(managerAuthorities())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
                        .with(user("manager").authorities(managerAuthorities()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/expenses").with(user("manager").authorities(managerAuthorities())))
                .andExpect(status().isOk())
                .andExpect(view().name("expense/list"));
        mockMvc.perform(post("/expenses/{id}/submit", EXPENSE_ID)
                        .with(user("manager").authorities(managerAuthorities()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** Confirms client JSON cannot override fixed currency, initial status, approver, or audit values. */
    @Test
    void shouldIgnoreClientSuppliedServerControlledFields() throws Exception {
        when(expenseService.create(any())).thenReturn(response("DRAFT"));
        String request = validRequest().replace(
                "}",
                ",\"currency\":\"USD\",\"status\":\"POSTED\",\"approvedBy\":\""
                        + UUID.randomUUID()
                        + "\",\"createdBy\":\""
                        + UUID.randomUUID()
                        + "\"}");

        mockMvc.perform(post("/api/expenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /** Confirms Expense v1 exposes neither DELETE nor category-management write endpoints. */
    @Test
    void shouldNotExposeExpenseDeleteOrCategoryWriteOperations() throws Exception {
        mockMvc.perform(delete("/api/expenses/{id}", EXPENSE_ID)
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/expense-categories")
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** Confirms the Expense list and explicit browser actions require MANAGE_EXPENSE and CSRF. */
    @Test
    void shouldRequireManageExpenseAndCsrfForMvcOperations() throws Exception {
        when(expenseService.findAll()).thenReturn(List.of());
        when(expenseService.findPage(any(), eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        when(expenseService.submit(EXPENSE_ID)).thenReturn(response("SUBMITTED"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpenseAuthority())))
                .andExpect(status().isOk())
                .andExpect(view().name("expense/list"));
        mockMvc.perform(get("/expenses").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/expenses/{id}/submit", EXPENSE_ID)
                        .with(user("admin").authorities(manageExpenseAuthority())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/expenses/{id}/submit", EXPENSE_ID)
                        .with(user("admin").authorities(manageExpenseAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** Confirms the sidebar renders an Expenses link when MANAGE_EXPENSE is granted. */
    @Test
    void shouldRenderExpenseNavLinkWhenManageExpenseGranted() throws Exception {
        when(expenseService.findAll()).thenReturn(List.of());
        when(expenseService.findPage(any(), eq(0)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/expenses").with(user("manager").authorities(managerAuthorities())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canManageExpense", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/expenses\"")));
    }

    /** Confirms the shared navigation advice exposes canManageExpense based on MANAGE_EXPENSE alone. */
    @Test
    void shouldExposeExpenseNavigationFlagFromNavigationModelAdvice() {
        ExtendedModelMap grantedModel = new ExtendedModelMap();
        new NavigationModelAdvice().addNavigationAttributes(
                grantedModel,
                new UsernamePasswordAuthenticationToken("manager", null, managerAuthorities()));
        assertTrue((Boolean) grantedModel.getAttribute("canManageExpense"));

        ExtendedModelMap deniedModel = new ExtendedModelMap();
        new NavigationModelAdvice().addNavigationAttributes(
                deniedModel,
                new UsernamePasswordAuthenticationToken("staff", null, staffAuthorities()));
        assertFalse((Boolean) deniedModel.getAttribute("canManageExpense"));
    }

    /** Confirms the terminal Expense posting action opts into danger confirmation metadata. */
    @Test
    void shouldRenderDangerConfirmationMetadataForExpensePosting() throws Exception {
        when(expenseService.findById(EXPENSE_ID)).thenReturn(response("APPROVED"));

        mockMvc.perform(get("/expenses/{id}", EXPENSE_ID)
                        .with(user("admin").authorities(manageExpenseAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-title=\"Post expense\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-label=\"Post expense\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-confirm-severity=\"DANGER\"")));
    }

    /** Builds the existing authority required by every Expense v1 operation. */
    private static List<SimpleGrantedAuthority> manageExpenseAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"));
    }

    /** Builds the current MANAGER authority set, which now includes MANAGE_EXPENSE. */
    private static List<SimpleGrantedAuthority> managerAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"));
    }

    /** Builds the current STAFF authority set, which intentionally omits MANAGE_EXPENSE. */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_IN"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /** Builds a valid client-controlled Expense JSON payload. */
    private String validRequest() {
        return "{\"categoryId\":\""
                + CATEGORY_ID
                + "\",\"amount\":100,\"expenseDate\":\"2026-09-11\","
                + "\"paymentMethod\":\"CASH\",\"description\":\"Utility bill\"}";
    }

    /** Creates a representative Expense response for controller tests. */
    private ExpenseResponse response(String status) {
        return new ExpenseResponse(
                EXPENSE_ID,
                new ExpenseCategoryResponse(CATEGORY_ID, "ELECTRICITY", "Electricity", null, true),
                BigDecimal.TEN,
                "VND",
                LocalDate.of(2026, 9, 11),
                "CASH",
                null,
                status,
                null);
    }

    /** Enables method-security interception for Expense controller tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
