package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.ExpenseCategoryCreateRequest;
import com.example.hotel.dto.common.request.ExpenseCategoryUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.service.common.ExpenseCategoryService;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves CSRF-protected Thymeleaf pages for configuring Expense v1 category reference data. */
@Controller
public class ExpenseCategoryPageController {

    private final ExpenseCategoryService expenseCategoryService;

    /**
     * Creates the Expense category page controller.
     *
     * @param expenseCategoryService service used to load and mutate Expense categories
     */
    public ExpenseCategoryPageController(ExpenseCategoryService expenseCategoryService) {
        this.expenseCategoryService = expenseCategoryService;
    }

    /**
     * Displays every Expense category, active and inactive.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the Expense category list template
     */
    @GetMapping("/expense-categories")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String list(Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("categories", expenseCategoryService.findAll());
        return "expense/category-list";
    }

    /**
     * Displays an empty Expense category creation form.
     *
     * @param model model used to render the form
     * @param authentication current browser authentication
     * @return the Expense category form template
     */
    @GetMapping("/expense-categories/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String createForm(Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("categoryForm", new ExpenseCategoryCreateRequest(null, null, null));
        return "expense/category-form";
    }

    /**
     * Creates a new active Expense category through a CSRF-protected browser form.
     *
     * @param categoryForm validated client-controlled category fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a list redirect or the form template after validation failure
     */
    @PostMapping("/expense-categories")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String create(
            @Valid @ModelAttribute("categoryForm") ExpenseCategoryCreateRequest categoryForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addAuthorizationAttributes(model, authentication);
            return "expense/category-form";
        }
        try {
            expenseCategoryService.create(categoryForm);
            redirectAttributes.addFlashAttribute("successMessage", "Expense category created successfully.");
            return "redirect:/expense-categories";
        } catch (ResponseStatusException exception) {
            addAuthorizationAttributes(model, authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "expense/category-form";
        }
    }

    /**
     * Displays the editable display fields of an existing Expense category.
     *
     * @param id ExpenseCategory identifier
     * @param model model used to render the form
     * @param authentication current browser authentication
     * @return the Expense category form template
     */
    @GetMapping("/expense-categories/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String updateForm(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        ExpenseCategoryResponse category = expenseCategoryService.findById(id);
        model.addAttribute("categoryForm", new ExpenseCategoryUpdateRequest(category.name(), category.description()));
        model.addAttribute("category", category);
        return "expense/category-form";
    }

    /**
     * Updates the display fields of an existing Expense category through a CSRF-protected form.
     *
     * @param id ExpenseCategory identifier
     * @param categoryForm validated replacement display fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a list redirect or the form template after validation failure
     */
    @PostMapping("/expense-categories/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("categoryForm") ExpenseCategoryUpdateRequest categoryForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addAuthorizationAttributes(model, authentication);
            model.addAttribute("category", expenseCategoryService.findById(id));
            return "expense/category-form";
        }
        try {
            expenseCategoryService.update(id, categoryForm);
            redirectAttributes.addFlashAttribute("successMessage", "Expense category updated successfully.");
            return "redirect:/expense-categories";
        } catch (ResponseStatusException exception) {
            addAuthorizationAttributes(model, authentication);
            model.addAttribute("category", expenseCategoryService.findById(id));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "expense/category-form";
        }
    }

    /**
     * Deactivates an active Expense category from a CSRF-protected browser form.
     *
     * @param id ExpenseCategory identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the Expense category list
     */
    @PostMapping("/expense-categories/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            expenseCategoryService.deactivate(id);
            redirectAttributes.addFlashAttribute("successMessage", "Expense category deactivated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/expense-categories";
    }

    /**
     * Reactivates an inactive Expense category from a CSRF-protected browser form.
     *
     * @param id ExpenseCategory identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the Expense category list
     */
    @PostMapping("/expense-categories/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    public String reactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            expenseCategoryService.reactivate(id);
            redirectAttributes.addFlashAttribute("successMessage", "Expense category reactivated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/expense-categories";
    }

    /**
     * Adds shared layout flags used by the template actions.
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
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by an Expense category operation
     * @return browser-safe error message
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
