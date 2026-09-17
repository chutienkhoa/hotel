package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.AdditionalRevenueCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueSearchCriteria;
import com.example.hotel.dto.common.request.AdditionalRevenueUpdateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueVoidRequest;
import com.example.hotel.dto.common.response.AdditionalRevenueResponse;
import com.example.hotel.service.common.AdditionalRevenueService;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Serves CSRF-protected MVC pages for Additional Revenue v1. */
@Controller
@PreAuthorize("hasAuthority('PERM_MANAGE_ADDITIONAL_REVENUE')")
public class AdditionalRevenuePageController {

    private final AdditionalRevenueService additionalRevenueService;

    public AdditionalRevenuePageController(AdditionalRevenueService additionalRevenueService) {
        this.additionalRevenueService = additionalRevenueService;
    }

    @GetMapping("/additional-revenues")
    public String list(
            @ModelAttribute("searchCriteria") AdditionalRevenueSearchCriteria searchCriteria,
            @RequestParam(required = false) Integer page,
            Model model) {
        model.addAttribute("categories", additionalRevenueService.findAllCategories());
        model.addAttribute("statuses", com.example.hotel.entity.common.AdditionalRevenueStatus.values());
        model.addAttribute("filterQueryString", filterQueryString(searchCriteria));
        model.addAttribute("filtersActive", searchCriteria.isAnyFilterActive());
        if (searchCriteria.isDateRangeInvalid()) {
            model.addAttribute("errorMessage", "From Date must not be after To Date.");
            model.addAttribute("revenuePage", Page.empty());
            return "additional-revenue/list";
        }
        Page<AdditionalRevenueResponse> revenuePage =
                additionalRevenueService.findPage(searchCriteria, page == null ? 0 : page);
        model.addAttribute("revenuePage", revenuePage);
        addPaginationAttributes(model, revenuePage);
        return "additional-revenue/list";
    }

    @GetMapping("/additional-revenues/new")
    public String createForm(Model model) {
        addCreateFormAttributes(model, new AdditionalRevenueCreateRequest(null, null, null, null, null));
        return "additional-revenue/form";
    }

    @PostMapping("/additional-revenues")
    public String create(
            @Valid @ModelAttribute("revenueForm") AdditionalRevenueCreateRequest revenueForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addCreateFormAttributes(model, revenueForm);
            return "additional-revenue/form";
        }
        try {
            AdditionalRevenueResponse revenue = additionalRevenueService.create(revenueForm);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue recorded successfully.");
            return "redirect:/additional-revenues/" + revenue.id();
        } catch (ResponseStatusException exception) {
            addCreateFormAttributes(model, revenueForm);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "additional-revenue/form";
        }
    }

    @GetMapping("/additional-revenues/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("revenue", additionalRevenueService.findById(id));
        model.addAttribute("voidForm", new AdditionalRevenueVoidRequest(null));
        return "additional-revenue/detail";
    }

    @GetMapping("/additional-revenues/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        AdditionalRevenueResponse revenue = additionalRevenueService.findById(id);
        if (!"RECORDED".equals(revenue.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only recorded Additional Revenue can be updated");
        }
        addEditFormAttributes(model, revenue, toUpdateRequest(revenue));
        return "additional-revenue/form";
    }

    @PostMapping("/additional-revenues/{id}")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("revenueForm") AdditionalRevenueUpdateRequest revenueForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        AdditionalRevenueResponse current = additionalRevenueService.findById(id);
        if (bindingResult.hasErrors()) {
            addEditFormAttributes(model, current, revenueForm);
            return "additional-revenue/form";
        }
        try {
            additionalRevenueService.update(id, revenueForm);
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue updated successfully.");
            return "redirect:/additional-revenues/" + id;
        } catch (ResponseStatusException exception) {
            addEditFormAttributes(model, current, revenueForm);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "additional-revenue/form";
        }
    }

    @PostMapping("/additional-revenues/{id}/void")
    public String voidRevenue(
            @PathVariable UUID id,
            @Valid @ModelAttribute("voidForm") AdditionalRevenueVoidRequest voidForm,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "A void reason is required.");
            return "redirect:/additional-revenues/" + id;
        }
        try {
            additionalRevenueService.voidRevenue(id, voidForm.voidReason());
            redirectAttributes.addFlashAttribute("successMessage", "Additional Revenue voided successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/additional-revenues/" + id;
    }

    private void addCreateFormAttributes(Model model, AdditionalRevenueCreateRequest form) {
        model.addAttribute("revenueForm", form);
        model.addAttribute("categories", additionalRevenueService.findActiveCategories());
    }

    private void addEditFormAttributes(Model model, AdditionalRevenueResponse revenue, Object form) {
        model.addAttribute("revenue", revenue);
        model.addAttribute("revenueForm", form);
        model.addAttribute("categories", additionalRevenueService.findSelectableCategoriesForEdit(revenue.id()));
    }

    private AdditionalRevenueUpdateRequest toUpdateRequest(AdditionalRevenueResponse revenue) {
        return new AdditionalRevenueUpdateRequest(
                revenue.category().id(),
                revenue.amount(),
                revenue.revenueDate(),
                com.example.hotel.entity.common.AdditionalRevenuePaymentMethod.valueOf(revenue.paymentMethod()),
                revenue.description());
    }

    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }

    private String filterQueryString(AdditionalRevenueSearchCriteria searchCriteria) {
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
        org.springframework.web.util.UriComponentsBuilder builder = org.springframework.web.util.UriComponentsBuilder.newInstance();
        filters.forEach(builder::queryParam);
        return builder.build().encode().getQuery();
    }

    private void addPaginationAttributes(Model model, Page<AdditionalRevenueResponse> revenuePage) {
        if (revenuePage.getTotalPages() == 0) {
            return;
        }
        int lastPage = revenuePage.getTotalPages() - 1;
        int startPage = Math.max(0, Math.min(revenuePage.getNumber() - 1, lastPage - 2));
        int endPage = Math.min(lastPage, startPage + 2);
        model.addAttribute("paginationStartPage", startPage);
        model.addAttribute("paginationEndPage", endPage);
    }
}
