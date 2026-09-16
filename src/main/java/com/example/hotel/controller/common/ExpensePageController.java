package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.request.ExpenseSearchCriteria;
import com.example.hotel.dto.common.request.ExpenseUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.service.common.ExpenseService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/** Serves CSRF-protected Thymeleaf pages for authorized Expense v1 operations. */
@Controller
public class ExpensePageController {

    private final ExpenseService expenseService;

    /**
     * Creates the Expense page controller.
     *
     * @param expenseService service used to load and mutate Expense v1 data
     */
    public ExpensePageController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * Displays a filtered, paginated page of Expenses available to Expense-management users.
     *
     * @param searchCriteria optional From Date, To Date, Category, and Status filters
     * @param page zero-based requested page number
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the Expense list template
     */
    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String list(
            @ModelAttribute("searchCriteria") ExpenseSearchCriteria searchCriteria,
            @RequestParam(required = false) Integer page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("categories", expenseService.findAllCategories());
        model.addAttribute("statuses", ExpenseStatus.values());
        model.addAttribute("filterQueryString", filterQueryString(searchCriteria));
        model.addAttribute("filtersActive", searchCriteria.isAnyFilterActive());

        if (searchCriteria.isDateRangeInvalid()) {
            model.addAttribute("errorMessage", "From Date must not be after To Date.");
            model.addAttribute("expensePage", Page.empty());
            return "expense/list";
        }

        Page<ExpenseResponse> expensePage = expenseService.findPage(searchCriteria, page == null ? 0 : page);
        model.addAttribute("expensePage", expensePage);
        addPaginationAttributes(model, expensePage);
        return "expense/list";
    }

    /**
     * Displays one Expense and only its currently valid lifecycle actions.
     *
     * @param id Expense identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the Expense detail template
     */
    @GetMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String detail(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("expense", expenseService.findById(id));
        return "expense/detail";
    }

    /**
     * Displays an empty Expense creation form with read-only category choices.
     *
     * @param model model used to render the form
     * @param authentication current browser authentication
     * @return the Expense form template
     */
    @GetMapping("/expenses/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String createForm(Model model, Authentication authentication) {
        addFormAttributes(
                model,
                new ExpenseCreateRequest(null, null, null, null, null),
                expenseService.findActiveCategories(),
                authentication);
        return "expense/form";
    }

    /**
     * Creates a draft Expense through a CSRF-protected browser form.
     *
     * @param expenseForm validated client-controlled Expense fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String create(
            @Valid @ModelAttribute("expenseForm") ExpenseCreateRequest expenseForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, expenseForm, expenseService.findActiveCategories(), authentication);
            return "expense/form";
        }
        try {
            ExpenseResponse expense = expenseService.create(expenseForm);
            redirectAttributes.addFlashAttribute("successMessage", "Expense created successfully.");
            return "redirect:/expenses/" + expense.id();
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, expenseForm, expenseService.findActiveCategories(), authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "expense/form";
        }
    }

    /**
     * Displays the editable business fields of a draft Expense.
     *
     * @param id Expense identifier
     * @param model model used to render the form
     * @param authentication current browser authentication
     * @return the Expense form template
     */
    @GetMapping("/expenses/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String updateForm(@PathVariable UUID id, Model model, Authentication authentication) {
        ExpenseResponse expense = expenseService.findById(id);
        addFormAttributes(model, toUpdateRequest(expense), selectableCategoriesForEdit(expense), authentication);
        model.addAttribute("expense", expense);
        return "expense/form";
    }

    /**
     * Updates a draft Expense through a CSRF-protected browser form.
     *
     * @param id Expense identifier
     * @param expenseForm validated replacement business fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/expenses/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("expenseForm") ExpenseUpdateRequest expenseForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            ExpenseResponse currentExpense = expenseService.findById(id);
            addFormAttributes(model, expenseForm, selectableCategoriesForEdit(currentExpense), authentication);
            model.addAttribute("expense", currentExpense);
            return "expense/form";
        }
        try {
            expenseService.update(id, expenseForm);
            redirectAttributes.addFlashAttribute("successMessage", "Expense updated successfully.");
            return "redirect:/expenses/" + id;
        } catch (ResponseStatusException exception) {
            ExpenseResponse currentExpense = expenseService.findById(id);
            addFormAttributes(model, expenseForm, selectableCategoriesForEdit(currentExpense), authentication);
            model.addAttribute("expense", currentExpense);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "expense/form";
        }
    }

    /**
     * Submits a draft Expense from a CSRF-protected browser form.
     *
     * @param id Expense identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect after the operation
     */
    @PostMapping("/expenses/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String submit(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, expenseService::submit, "Expense submitted successfully.", redirectAttributes);
    }

    /**
     * Approves a submitted Expense from a CSRF-protected browser form.
     *
     * @param id Expense identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect after the operation
     */
    @PostMapping("/expenses/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String approve(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, expenseService::approve, "Expense approved successfully.", redirectAttributes);
    }

    /**
     * Rejects a submitted Expense from a CSRF-protected browser form.
     *
     * @param id Expense identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect after the operation
     */
    @PostMapping("/expenses/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String reject(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, expenseService::reject, "Expense rejected successfully.", redirectAttributes);
    }

    /**
     * Posts an approved Expense from a CSRF-protected browser form without accounting integration.
     *
     * @param id Expense identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect after the operation
     */
    @PostMapping("/expenses/{id}/post")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String post(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return executeOperation(id, expenseService::post, "Expense posted successfully.", redirectAttributes);
    }

    /**
     * Executes one explicit Expense lifecycle operation and redirects to its detail page.
     *
     * @param id Expense identifier
     * @param operation service operation to execute
     * @param successMessage feedback displayed after success
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect after operation handling
     */
    private String executeOperation(
            UUID id,
            Function<UUID, ExpenseResponse> operation,
            String successMessage,
            RedirectAttributes redirectAttributes) {
        try {
            operation.apply(id);
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/expenses/" + id;
    }

    /**
     * Adds shared layout flags and the Expense permission flag used by the template actions.
     *
     * @param model model used to render a page
     * @param authentication current browser authentication
     */
    private void addAuthorizationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canManageBooking", hasAuthority(authentication, "PERM_MANAGE_BOOKING"));
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
        model.addAttribute("canManageExpense", hasAuthority(authentication, "PERM_MANAGE_EXPENSE"));
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
    }

    /**
     * Adds categories, form data, and shared layout flags required by the Expense form.
     *
     * @param model model used to render the form
     * @param expenseForm create or update form data
     * @param categories the categories selectable in this form's dropdown
     * @param authentication current browser authentication
     */
    private void addFormAttributes(
            Model model, Object expenseForm, List<ExpenseCategoryResponse> categories, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("expenseForm", expenseForm);
        model.addAttribute("categories", categories);
    }

    /**
     * Builds the categories selectable while editing a draft Expense: every active category, plus
     * the Expense's own current category when that category has since become inactive, so an
     * existing assignment can remain visible and unchanged without offering unrelated inactive
     * categories.
     *
     * @param expense the draft Expense being edited
     * @return the selectable category list for the edit form
     */
    private List<ExpenseCategoryResponse> selectableCategoriesForEdit(ExpenseResponse expense) {
        List<ExpenseCategoryResponse> active = expenseService.findActiveCategories();
        ExpenseCategoryResponse current = expense.category();
        if (current.active() || active.stream().anyMatch(category -> category.id().equals(current.id()))) {
            return active;
        }
        List<ExpenseCategoryResponse> selectable = new ArrayList<>(active);
        selectable.add(current);
        selectable.sort(Comparator.comparing(ExpenseCategoryResponse::code));
        return selectable;
    }

    /**
     * Builds the encoded query string carrying every populated Expense list filter.
     *
     * @param searchCriteria optional Expense list filters
     * @return the encoded filter query string, or an empty string when no filter is populated
     */
    private String filterQueryString(ExpenseSearchCriteria searchCriteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (searchCriteria.getFromDate() != null) {
            filters.put("fromDate", searchCriteria.getFromDate().toString());
        }
        if (searchCriteria.getToDate() != null) {
            filters.put("toDate", searchCriteria.getToDate().toString());
        }
        if (searchCriteria.getCategoryId() != null) {
            filters.put("categoryId", searchCriteria.getCategoryId().toString());
        }
        if (searchCriteria.getStatus() != null) {
            filters.put("status", searchCriteria.getStatus().name());
        }
        if (filters.isEmpty()) {
            return "";
        }
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance();
        filters.forEach(builder::queryParam);
        return builder.build().encode().getQuery();
    }

    /**
     * Adds the bounded pagination window used to render a compact Expense page-number list.
     *
     * @param model model used to render the page
     * @param expensePage the loaded Expense page
     */
    private void addPaginationAttributes(Model model, Page<ExpenseResponse> expensePage) {
        int totalPages = expensePage.getTotalPages();
        if (totalPages == 0) {
            return;
        }
        int lastPage = totalPages - 1;
        int startPage = Math.max(0, Math.min(expensePage.getNumber() - 1, lastPage - 2));
        int endPage = Math.min(lastPage, startPage + 2);
        model.addAttribute("paginationStartPage", startPage);
        model.addAttribute("paginationEndPage", endPage);
    }

    /**
     * Determines whether the current session includes an authority.
     *
     * @param authentication current browser authentication
     * @param authority required permission authority
     * @return {@code true} when the authority is present
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    /**
     * Converts one Expense response into the fields editable by a draft update form.
     *
     * @param expense Expense response used to populate the form
     * @return mutable draft fields without server-controlled values
     */
    private ExpenseUpdateRequest toUpdateRequest(ExpenseResponse expense) {
        return new ExpenseUpdateRequest(
                expense.category().id(),
                expense.amount(),
                expense.expenseDate(),
                com.example.hotel.entity.common.ExpensePaymentMethod.valueOf(expense.paymentMethod()),
                expense.description());
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by an Expense operation
     * @return browser-safe error message
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
