package com.example.hotel.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.common.ExpenseService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Expense page rendering conforms to the shared money-input, money, and date conventions. */
@WebMvcTest(ExpensePageController.class)
@Import(ExpensePageControllerTest.MethodSecurityTestConfiguration.class)
class ExpensePageControllerTest {

    private static final UUID EXPENSE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseService expenseService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the Amount field is a text money input using the shared js-money-input convention. */
    @Test
    void shouldRenderAmountAsTextMoneyInput() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(get("/expenses/new").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<input class=\"js-money-input\" id=\"amount\" inputmode=\"decimal\"")))
                .andExpect(content().string(containsString("id=\"amount\" inputmode=\"decimal\" required type=\"text\"")))
                .andExpect(content().string(not(containsString("id=\"amount\" min="))));
    }

    /** Confirms the Expense date input remains an HTML date input, unaffected by display formatting. */
    @Test
    void shouldKeepExpenseDateHtmlInputUnchanged() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(get("/expenses/new").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "id=\"expenseDate\" name=\"expenseDate\" required type=\"date\"")));
    }

    /** Confirms Edit Expense renders the ISO yyyy-MM-dd value HTML date inputs require, not a locale format. */
    @Test
    void shouldRenderExpenseDateInputAsIsoOnEdit() throws Exception {
        when(expenseService.findById(EXPENSE_ID)).thenReturn(response("DRAFT"));

        mockMvc.perform(get("/expenses/{id}/edit", EXPENSE_ID).with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2026-09-11\"")))
                .andExpect(content().string(not(containsString("value=\"9/11/26\""))))
                .andExpect(content().string(not(containsString("value=\"11/9/26\""))));
    }

    /** Confirms a validation-error redisplay still renders the Expense date input as ISO yyyy-MM-dd. */
    @Test
    void shouldPreserveIsoExpenseDateOnValidationErrorRedisplay() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(post("/expenses")
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("amount", "0")
                        .param("expenseDate", "2026-09-06")
                        .param("paymentMethod", "CASH")
                        .with(user("admin").authorities(manageExpense()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2026-09-06\"")));
    }

    /** Confirms a submitted plain numeric amount (post comma-stripping) still binds to BigDecimal correctly. */
    @Test
    void shouldBindPlainNumericAmountToBigDecimal() throws Exception {
        when(expenseService.create(any())).thenReturn(response("DRAFT"));

        mockMvc.perform(post("/expenses")
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("amount", "1000000")
                        .param("expenseDate", "2026-09-11")
                        .param("paymentMethod", "CASH")
                        .with(user("admin").authorities(manageExpense()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ExpenseCreateRequest> captor = ArgumentCaptor.forClass(ExpenseCreateRequest.class);
        org.mockito.Mockito.verify(expenseService).create(captor.capture());
        assertEquals(0, new BigDecimal("1000000").compareTo(captor.getValue().amount()));
    }

    /** Confirms a validation-error redisplay preserves the entered Amount without an inflated scale. */
    @Test
    void shouldPreserveEnteredAmountOnValidationErrorRedisplay() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(post("/expenses")
                        .param("amount", "1000000")
                        .param("expenseDate", "2026-09-11")
                        .param("paymentMethod", "CASH")
                        .with(user("admin").authorities(manageExpense()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"1000000\"")))
                .andExpect(content().string(not(containsString("1000000.000000"))));
    }

    /** Confirms the Expense list renders comma-grouped money via the shared fragment, not raw concatenation. */
    @Test
    void shouldRenderListAmountUsingSharedMoneyFragment() throws Exception {
        stubExpensePage(response("DRAFT"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1,500,000 VND")))
                .andExpect(content().string(not(containsString("1500000.000000 VND"))));
    }

    /** Confirms the Expense detail page renders comma-grouped money via the shared fragment. */
    @Test
    void shouldRenderDetailAmountUsingSharedMoneyFragment() throws Exception {
        when(expenseService.findById(EXPENSE_ID)).thenReturn(response("DRAFT"));

        mockMvc.perform(get("/expenses/{id}", EXPENSE_ID).with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1,500,000 VND")))
                .andExpect(content().string(not(containsString("1500000.000000 VND"))));
    }

    /** Confirms the Expense date displays using the project-wide dd/MM/yyyy standard, not the raw ISO string. */
    @Test
    void shouldRenderExpenseDateUsingProjectDateStandard() throws Exception {
        stubExpensePage(response("DRAFT"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("11/09/2026")))
                .andExpect(content().string(not(containsString("2026-09-11"))));
    }

    /** Stubs {@code findPage} to return a single-page result containing the supplied Expenses. */
    private void stubExpensePage(ExpenseResponse... expenses) {
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(expenses), PageRequest.of(0, 20), expenses.length));
    }

    /** Confirms the filter form exposes fromDate, toDate, categoryId, and status controls. */
    @Test
    void shouldRenderFilterFormFields() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        stubExpensePage(response("DRAFT"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "class=\"js-date-picker\" id=\"fromDate\" name=\"fromDate\"")))
                .andExpect(content().string(containsString(
                        "class=\"js-date-picker\" id=\"toDate\" name=\"toDate\"")))
                .andExpect(content().string(containsString("<select id=\"categoryId\" name=\"categoryId\">")))
                .andExpect(content().string(containsString("<select id=\"status\" name=\"status\">")))
                .andExpect(content().string(containsString("All categories")))
                .andExpect(content().string(containsString("All statuses")));
    }

    /**
     * Confirms the Expense List From/To Date inputs use the same date-picker convention as Create
     * Reservation: text input, {@code js-date-picker} class, ISO value for LocalDate binding.
     */
    @Test
    void shouldUseReservationDatePickerConventionForFromAndToDate() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        stubExpensePage(response("DRAFT"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<input class=\"js-date-picker\" id=\"fromDate\" name=\"fromDate\"")))
                .andExpect(content().string(containsString(
                        "<input class=\"js-date-picker\" id=\"toDate\" name=\"toDate\"")))
                .andExpect(content().string(not(containsString("id=\"fromDate\" name=\"fromDate\" type=\"date\""))))
                .andExpect(content().string(not(containsString("id=\"toDate\" name=\"toDate\" type=\"date\""))));
    }

    /** Confirms selected filter values are preserved after the response redisplays the form. */
    @Test
    void shouldPreserveSelectedFilterValuesAfterResponse() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        stubExpensePage(response("POSTED"));

        mockMvc.perform(get("/expenses")
                        .param("fromDate", "2026-09-01")
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("status", "POSTED")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"2026-09-01\"")))
                .andExpect(content().string(containsString("value=\"" + CATEGORY_ID + "\" selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"POSTED\" selected=\"selected\"")));
    }

    /** Confirms Clear navigates to the unfiltered Expense list route. */
    @Test
    void shouldPointClearToUnfilteredExpenseList() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        stubExpensePage(response("DRAFT"));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/expenses\">Clear</a>")));
    }

    /** Confirms pagination links preserve every active filter while changing only the page. */
    @Test
    void shouldPreserveFiltersInPaginationLinks() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(response("DRAFT")), PageRequest.of(0, 20), 25));

        mockMvc.perform(get("/expenses")
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("status", "DRAFT")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/expenses?categoryId=" + CATEGORY_ID + "&amp;status=DRAFT&amp;page=1\"")));
    }

    /** Confirms Previous is absent (disabled) on the first page and Next is absent on the last page. */
    @Test
    void shouldDisablePreviousOnFirstPageAndNextOnLastPage() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(response("DRAFT")), PageRequest.of(0, 20), 21));

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Previous"))))
                .andExpect(content().string(containsString("Next")));

        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(new PageImpl<>(List.of(response("DRAFT")), PageRequest.of(1, 20), 21));

        mockMvc.perform(get("/expenses").param("page", "1")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Previous")))
                .andExpect(content().string(not(containsString("Next"))));
    }

    /** Confirms a filtered zero-result page shows the filtered empty-state message with a clear link. */
    @Test
    void shouldShowFilteredEmptyStateWhenNoExpensesMatch() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(0))).thenReturn(Page.empty());

        mockMvc.perform(get("/expenses").param("status", "REJECTED")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No expenses match the selected filters.")))
                .andExpect(content().string(containsString("Clear filters")));
    }

    /** Confirms an unfiltered zero-result page shows the plain "no expenses" message, not the filtered one. */
    @Test
    void shouldShowPlainEmptyStateWhenNoExpensesExistAndNoFilterApplied() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(0))).thenReturn(Page.empty());

        mockMvc.perform(get("/expenses").with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No expenses found.")))
                .andExpect(content().string(not(containsString("No expenses match the selected filters."))));
    }

    /** Confirms fromDate after toDate shows a friendly validation message and preserves entered filters. */
    @Test
    void shouldShowFriendlyValidationMessageWhenFromDateAfterToDate() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(get("/expenses")
                        .param("fromDate", "2026-09-30")
                        .param("toDate", "2026-09-01")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("From Date must not be after To Date.")))
                .andExpect(content().string(containsString("value=\"2026-09-30\"")))
                .andExpect(content().string(containsString("value=\"2026-09-01\"")));
        org.mockito.Mockito.verify(expenseService, org.mockito.Mockito.never()).findPage(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** Confirms the REST list contract remains a plain List, not a Page. */
    @Test
    void shouldKeepRestExpenseListContractAsList() throws NoSuchMethodException {
        assertEquals(List.class, ExpenseService.class.getMethod("findAll").getReturnType());
    }

    /** Builds the existing MANAGE_EXPENSE authority. */
    private static List<SimpleGrantedAuthority> manageExpense() {
        return List.of(new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"));
    }

    /** Creates a representative Expense category fixture. */
    private ExpenseCategoryResponse category() {
        return new ExpenseCategoryResponse(CATEGORY_ID, "ELECTRICITY", "Electricity", null, true);
    }

    /** Creates a representative Expense response with a non-round amount to expose formatting bugs. */
    private ExpenseResponse response(String status) {
        return new ExpenseResponse(
                EXPENSE_ID,
                category(),
                new BigDecimal("1500000.000000"),
                "VND",
                LocalDate.of(2026, 9, 11),
                "CASH",
                "Utility bill",
                status,
                null);
    }

    /** Enables method-security interception for Expense page tests. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}

    /** Confirms an out-of-range Expense page redirects to the last valid page preserving filter and sort. */
    @Test
    void shouldRedirectOutOfRangeExpensePagePreservingFilterAndSort() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));
        when(expenseService.findPage(any(), org.mockito.ArgumentMatchers.eq(9)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 20), 41));

        mockMvc.perform(get("/expenses").param("page", "9").param("status", "DRAFT")
                        .param("sort", "amount").param("dir", "desc")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/expenses?status=DRAFT&sort=amount&dir=desc&page=2"));
    }

    /** Confirms an invalid date range shows the friendly error, keeps the entered dates, and runs no search. */
    @Test
    void shouldRejectFromDateAfterToDateWithoutSearchingAndKeepFilters() throws Exception {
        when(expenseService.findAllCategories()).thenReturn(List.of(category()));

        mockMvc.perform(get("/expenses").param("fromDate", "2026-09-30").param("toDate", "2026-09-01")
                        .with(user("admin").authorities(manageExpense())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("From Date must not be after To Date.")))
                .andExpect(content().string(containsString("value=\"2026-09-30\"")));

        org.mockito.Mockito.verify(expenseService, org.mockito.Mockito.never()).findPage(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
