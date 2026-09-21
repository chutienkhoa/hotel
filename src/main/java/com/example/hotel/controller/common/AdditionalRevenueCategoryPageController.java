package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.AdditionalRevenueCategoryCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueCategoryUpdateRequest;
import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.service.common.AdditionalRevenueCategoryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves CSRF-protected MVC pages for Additional Revenue category configuration. */
@Controller
@PreAuthorize("hasAuthority('PERM_MANAGE_ADDITIONAL_REVENUE')")
public class AdditionalRevenueCategoryPageController {

    private final AdditionalRevenueCategoryService categoryService;

    public AdditionalRevenueCategoryPageController(AdditionalRevenueCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/additional-revenue-categories")
    public String list(Model model) {
        model.addAttribute("categories", categoryService.findAll());
        return "additional-revenue/category-list";
    }

    @GetMapping("/additional-revenue-categories/new")
    public String createForm(Model model) {
        model.addAttribute("categoryForm", new AdditionalRevenueCategoryCreateRequest(null, null, null));
        return "additional-revenue/category-form";
    }

    @PostMapping("/additional-revenue-categories")
    public String create(
            @Valid @ModelAttribute("categoryForm") AdditionalRevenueCategoryCreateRequest categoryForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "additional-revenue/category-form";
        }
        try {
            categoryService.create(categoryForm);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue category created successfully.");
            return "redirect:/additional-revenue-categories";
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            return "additional-revenue/category-form";
        }
    }

    @GetMapping("/additional-revenue-categories/{id}/edit")
    public String updateForm(@PathVariable UUID id, Model model) {
        AdditionalRevenueCategoryResponse category = categoryService.findById(id);
        model.addAttribute("categoryForm", new AdditionalRevenueCategoryUpdateRequest(category.name(), category.description()));
        model.addAttribute("category", category);
        return "additional-revenue/category-form";
    }

    @PostMapping("/additional-revenue-categories/{id}")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("categoryForm") AdditionalRevenueCategoryUpdateRequest categoryForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("category", categoryService.findById(id));
            return "additional-revenue/category-form";
        }
        try {
            categoryService.update(id, categoryForm);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue category updated successfully.");
            return "redirect:/additional-revenue-categories";
        } catch (ResponseStatusException exception) {
            model.addAttribute("category", categoryService.findById(id));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "additional-revenue/category-form";
        }
    }

    @PostMapping("/additional-revenue-categories/{id}/deactivate")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            categoryService.deactivate(id);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue category deactivated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/additional-revenue-categories";
    }

    @PostMapping("/additional-revenue-categories/{id}/reactivate")
    public String reactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            categoryService.reactivate(id);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue category reactivated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/additional-revenue-categories";
    }

    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
