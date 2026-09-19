package com.example.hotel.controller.common;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.common.request.StaffCreateRequest;
import com.example.hotel.dto.common.request.StaffSearchCriteria;
import com.example.hotel.dto.common.request.StaffUpdateRequest;
import com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.service.common.DailyWorkRecordService;
import com.example.hotel.service.common.StaffService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
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

/** Serves CSRF-protected Thymeleaf pages for Staff Management. */
@Controller
public class StaffPageController {

    private final StaffService staffService;
    private final DailyWorkRecordService dailyWorkRecordService;
    private final Clock clock;
    private final UiMessages messages;

    /**
     * Creates the Staff page controller.
     *
     * @param staffService service used to load and mutate Staff members
     * @param dailyWorkRecordService service used to load the Staff Detail Work History
     * @param clock authoritative hotel business clock used to default the Work History range
     * @param messageSource message source used to translate user-facing messages
     */
    public StaffPageController(
            StaffService staffService,
            DailyWorkRecordService dailyWorkRecordService,
            Clock clock,
            MessageSource messageSource) {
        this.staffService = staffService;
        this.dailyWorkRecordService = dailyWorkRecordService;
        this.clock = clock;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays every Staff member matching the optional free-text and status filters.
     *
     * @param searchCriteria submitted free-text/status filters
     * @param model model used to render the page
     * @return the Staff list template
     */
    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String list(@ModelAttribute("searchCriteria") StaffSearchCriteria searchCriteria, Model model) {
        searchCriteria.normalize();
        model.addAttribute("staffList", staffService.search(searchCriteria));
        return "staff/list";
    }

    /**
     * Displays one Staff member's profile together with their Work History (by Staff), the
     * complement to the by-date Daily Work Record screen. Only calendar dates with an actual
     * Daily Work Record are shown; a missing date is never synthesized into a row.
     *
     * @param id Staff identifier
     * @param fromDate optional inclusive lower bound of the Work History range; defaults to the
     *     first day of the current hotel month
     * @param toDate optional inclusive upper bound of the Work History range; defaults to the
     *     current hotel date
     * @param model model used to render the page
     * @return the Staff Detail template
     */
    @GetMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String detail(
            @PathVariable UUID id,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveFrom = fromDate != null ? fromDate : today.withDayOfMonth(1);
        LocalDate effectiveTo = toDate != null ? toDate : today;

        model.addAttribute("staff", staffService.findById(id));
        model.addAttribute("fromDate", effectiveFrom);
        model.addAttribute("toDate", effectiveTo);
        if (effectiveFrom.isAfter(effectiveTo)) {
            model.addAttribute("errorMessage", messages.get("staff.error.dateRange"));
            model.addAttribute("workHistory", List.<DailyWorkRecordHistoryLine>of());
        } else {
            model.addAttribute("workHistory", dailyWorkRecordService.history(id, effectiveFrom, effectiveTo));
        }
        return "staff/detail";
    }

    /**
     * Displays an empty Staff creation form.
     *
     * @param model model used to render the form
     * @return the Staff form template
     */
    @GetMapping("/staff/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String createForm(Model model) {
        model.addAttribute("staffForm", new StaffCreateRequest(null, null, null, null, null, null, null));
        return "staff/form";
    }

    /**
     * Creates a new Staff member through a CSRF-protected browser form. The Staff Code is
     * generated by the backend and never accepted from the client.
     *
     * @param staffForm validated client-controlled Staff profile fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a list redirect or the form template after validation failure
     */
    @PostMapping("/staff")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String create(
            @Valid @ModelAttribute("staffForm") StaffCreateRequest staffForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("languageSwitchPath", "/staff/new");
            return "staff/form";
        }
        try {
            StaffResponse staff = staffService.create(staffForm);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("staff.flash.created", staff.staffCode()));
            return "redirect:/staff";
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", messages.error(exception));
            model.addAttribute("languageSwitchPath", "/staff/new");
            return "staff/form";
        }
    }

    /**
     * Displays the editable profile fields of an existing Staff member. The Staff Code is shown
     * read-only.
     *
     * @param id Staff identifier
     * @param model model used to render the form
     * @return the Staff form template
     */
    @GetMapping("/staff/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String updateForm(@PathVariable UUID id, Model model) {
        StaffResponse staff = staffService.findById(id);
        model.addAttribute(
                "staffForm",
                new StaffUpdateRequest(
                        staff.firstName(),
                        staff.lastName(),
                        staff.phone(),
                        staff.email(),
                        staff.position(),
                        staff.startDate(),
                        staff.notes()));
        model.addAttribute("staff", staff);
        return "staff/form";
    }

    /**
     * Updates the editable profile fields of an existing Staff member through a CSRF-protected
     * form. The Staff Code and active state are never changed here.
     *
     * @param id Staff identifier
     * @param staffForm validated replacement profile fields
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a list redirect or the form template after validation failure
     */
    @PostMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("staffForm") StaffUpdateRequest staffForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("staff", staffService.findById(id));
            model.addAttribute("languageSwitchPath", "/staff/" + id + "/edit");
            return "staff/form";
        }
        try {
            StaffResponse staff = staffService.update(id, staffForm);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("staff.flash.updated", staff.staffCode()));
            return "redirect:/staff";
        } catch (ResponseStatusException exception) {
            model.addAttribute("staff", staffService.findById(id));
            model.addAttribute("errorMessage", messages.error(exception));
            model.addAttribute("languageSwitchPath", "/staff/" + id + "/edit");
            return "staff/form";
        }
    }

    /**
     * Deactivates an active Staff member from a CSRF-protected browser form.
     *
     * @param id Staff identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the Staff list
     */
    @PostMapping("/staff/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            StaffResponse staff = staffService.deactivate(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("staff.flash.deactivated", staff.staffCode()));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.error(exception));
        }
        return "redirect:/staff";
    }

    /**
     * Reactivates an inactive Staff member from a CSRF-protected browser form.
     *
     * @param id Staff identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the Staff list
     */
    @PostMapping("/staff/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_STAFF')")
    public String reactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            StaffResponse staff = staffService.reactivate(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("staff.flash.reactivated", staff.staffCode()));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.error(exception));
        }
        return "redirect:/staff";
    }
}
