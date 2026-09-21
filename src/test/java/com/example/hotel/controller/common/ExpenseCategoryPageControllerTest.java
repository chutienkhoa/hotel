package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.ExpenseCategoryService;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Expense Category Management authorization, CRUD, lifecycle UI, and Expense dropdown behavior. */
@WebMvcTest({ExpenseCategoryPageController.class, ExpensePageController.class})
@Import(ExpenseCategoryPageControllerTest.MethodSecurityTestConfiguration.class)
class ExpenseCategoryPageControllerTest {

    private static final UUID CATEGORY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXPENSE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseCategoryService expenseCategoryService;

    @MockitoBean
    private ExpenseService expenseService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN and MANAGER can list, create, edit, deactivate, and reactivate categories. */
    @Test
    void shouldAllowAdministrativeRolesToManageCategories() throws Exception {
        when(expenseCategoryService.findAll()).thenReturn(List.of(activeCategory()));
        when(expenseCategoryService.findById(CATEGORY_ID)).thenReturn(activeCategory());
        when(expenseCategoryService.create(any())).thenReturn(activeCategory());
        when(expenseCategoryService.update(eq(CATEGORY_ID), any())).thenReturn(activeCategory());

        for (String username : List.of("admin", "manager")) {
            mockMvc.perform(get("/expense-categories").with(user(username).authorities(manageExpense())))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/expense-categories/new").with(user(username).authorities(manageExpense())))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/expense-categories")
                            .param("code", "REPAIR")
                            .param("name", "Repair")
                            .with(user(username).authorities(manageExpense()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/expense-categories/{id}/edit", CATEGORY_ID)
                            .with(user(username).authorities(manageExpense())))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/expense-categories/{id}", CATEGORY_ID)
                            .param("name", "Electricity")
                            .with(user(username).authorities(manageExpense()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(post("/expense-categories/{id}/deactivate", CATEGORY_ID)
                            .with(user(username).authorities(manageExpense()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(post("/expense-categories/{id}/reactivate", CATEGORY_ID)
                            .with(user(username).authorities(manageExpense()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
    }

    /** Confirms STAFF cannot access any Expense Category Management route. */
    @Test
    void shouldRejectStaffFromCategoryManagement() throws Exception {
        mockMvc.perform(get("/expense-categories").with(user("staff").authorities(staffAuthorities())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/expense-categories")
                        .param("code", "REPAIR")
                        .param("name", "Repair")
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/expense-categories/{id}/deactivate", CATEGORY_ID)
                        .with(user("staff").authorities(staffAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms no DELETE endpoint/action exists for Expense categories. The route
     * {@code /expense-categories/{id}} is mapped for update (POST) only, so DELETE correctly
     * resolves as an unsupported method rather than any delete operation being served.
     */
    @Test
    void shouldNotExposeCategoryDeleteOperation() throws Exception {
        mockMvc.perform(delete("/expense-categories/{id}", CATEGORY_ID)
                        .with(user("admin").authorities(manageExpense()))
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Confirms the category list shows active/inactive status with the correct lifecycle action. */
    @Test
    void shouldShowActiveDeactivateAndInactiveReactivateActions() throws Exception {
        when(expenseCategoryService.findAll()).thenReturn(List.of(activeCategory(), inactiveCategory()));

        mockMvc.perform(get("/expense-categories").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ACTIVE")))
                .andExpect(content().string(containsString("INACTIVE")))
                .andExpect(content().string(containsString(">Deactivate</button>")))
                .andExpect(content().string(containsString(">Reactivate</button>")));
    }

    /** Confirms the edit form does not expose the category code as an editable field. */
    @Test
    void shouldNotExposeCodeAsEditableOnEditForm() throws Exception {
        when(expenseCategoryService.findById(CATEGORY_ID)).thenReturn(activeCategory());

        mockMvc.perform(get("/expense-categories/{id}/edit", CATEGORY_ID)
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("disabled")))
                .andExpect(content().string(not(containsString("name=\"code\""))));
    }

    /** Confirms a duplicate-code conflict redisplays the create form with a friendly error, not a stack trace. */
    @Test
    void shouldRedisplayFormOnDuplicateCodeConflict() throws Exception {
        when(expenseCategoryService.create(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Expense category code already exists"));

        mockMvc.perform(post("/expense-categories")
                        .param("code", "ELECTRICITY")
                        .param("name", "Electricity")
                        .with(user("admin").authorities(manageExpense()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Expense category code already exists")));
    }

    /** Confirms the Create Expense form offers only active categories. */
    @Test
    void shouldOfferOnlyActiveCategoriesOnCreateExpenseForm() throws Exception {
        when(expenseService.findActiveCategories()).thenReturn(List.of(activeCategory()));

        mockMvc.perform(get("/expenses/new").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ELECTRICITY")))
                .andExpect(content().string(not(containsString("OLD_CATEGORY"))));
        verify(expenseService, never()).findAllCategories();
    }

    /** Confirms the edit-DRAFT-Expense form includes the current inactive category, marked "(Inactive)". */
    @Test
    void shouldIncludeCurrentInactiveCategoryMarkedOnEditExpenseForm() throws Exception {
        when(expenseService.findActiveCategories()).thenReturn(List.of(activeCategory()));
        when(expenseService.findById(EXPENSE_ID)).thenReturn(expenseWithCategory(inactiveCategory()));

        mockMvc.perform(get("/expenses/{id}/edit", EXPENSE_ID).with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ELECTRICITY")))
                .andExpect(content().string(containsString("OLD_CATEGORY (Inactive)")));
    }

    /** Confirms unrelated inactive categories (not the Expense's own category) are absent from the edit dropdown. */
    @Test
    void shouldOmitUnrelatedInactiveCategoriesFromEditExpenseForm() throws Exception {
        when(expenseService.findActiveCategories()).thenReturn(List.of(activeCategory()));
        when(expenseService.findById(EXPENSE_ID)).thenReturn(expenseWithCategory(activeCategory()));

        mockMvc.perform(get("/expenses/{id}/edit", EXPENSE_ID).with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("OLD_CATEGORY"))));
    }

    /** Confirms the Expenses list page links to Manage Categories. */
    @Test
    void shouldLinkFromExpenseListToManageCategories() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(activeCategory()));
        when(expenseService.findPage(any(), eq(0))).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/expense-categories\">Manage Categories</a>")));
    }

    /** Builds the existing MANAGE_EXPENSE authority, shared by ADMIN and MANAGER in this test. */
    private static List<SimpleGrantedAuthority> manageExpense() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"));
    }

    /** Builds the current STAFF authority set, which intentionally omits MANAGE_EXPENSE. */
    private static List<SimpleGrantedAuthority> staffAuthorities() {
        return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /** Creates a representative active Expense category fixture. */
    private ExpenseCategoryResponse activeCategory() {
        return new ExpenseCategoryResponse(CATEGORY_ID, "ELECTRICITY", "Electricity", null, true);
    }

    /** Creates a representative inactive Expense category fixture. */
    private ExpenseCategoryResponse inactiveCategory() {
        return new ExpenseCategoryResponse(UUID.randomUUID(), "OLD_CATEGORY", "Old Category", null, false);
    }

    /** Creates a draft Expense response assigned to the supplied category. */
    private ExpenseResponse expenseWithCategory(ExpenseCategoryResponse category) {
        return new ExpenseResponse(
                EXPENSE_ID, category, BigDecimal.TEN, "VND", LocalDate.of(2026, 9, 11), "CASH", null, "DRAFT", null);
    }

    /** Enables method-security interception for Expense category page tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
