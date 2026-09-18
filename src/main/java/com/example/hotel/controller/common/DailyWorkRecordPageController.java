package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.DailyWorkRecordFormRequest;
import com.example.hotel.dto.common.request.StaffSearchCriteria;
import com.example.hotel.dto.common.response.DailyWorkRecordHistoryLine;
import com.example.hotel.service.common.DailyWorkRecordService;
import com.example.hotel.service.common.StaffService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the CSRF-protected Thymeleaf Daily Work Record screens: the By Date bulk entry screen
 * and the read-only By Staff Work History search.
 */
@Controller
public class DailyWorkRecordPageController {

    private final DailyWorkRecordService dailyWorkRecordService;
    private final StaffService staffService;
    private final Clock clock;

    /**
     * Creates the Daily Work Record page controller.
     *
     * @param dailyWorkRecordService service used to load/save Daily Work Record entries and Work
     *     History
     * @param staffService service used to populate the By Staff selector with every Staff member
     * @param clock authoritative hotel business clock used to default selected dates
     */
    public DailyWorkRecordPageController(
            DailyWorkRecordService dailyWorkRecordService, StaffService staffService, Clock clock) {
        this.dailyWorkRecordService = dailyWorkRecordService;
        this.staffService = staffService;
        this.clock = clock;
    }

    /**
     * Displays one editable line per active Staff member for the selected (or current hotel)
     * work date.
     *
     * @param workDate optional selected work date; defaults to the current hotel date
     * @param model model used to render the page
     * @return the Daily Work Record template
     */
    @GetMapping("/staff/daily-work-record")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ATTENDANCE')")
    public String show(@RequestParam(required = false) LocalDate workDate, Model model) {
        LocalDate selectedDate = workDate != null ? workDate : LocalDate.now(clock);
        DailyWorkRecordFormRequest form = new DailyWorkRecordFormRequest();
        form.setWorkDate(selectedDate);
        form.setLines(dailyWorkRecordService.loadLines(selectedDate));
        model.addAttribute("form", form);
        return "staff/daily-work-record";
    }

    /**
     * Displays the read-only By Staff Work History search: one selected Staff member's Daily
     * Work Record entries within an inclusive date range, newest work date first. Both active
     * and inactive Staff can be selected, since historical records for inactive Staff must
     * remain searchable. This screen never creates or edits any Daily Work Record.
     *
     * @param staffId optional selected Staff identifier; no search runs until one is selected
     * @param fromDate optional inclusive lower bound of the search range; defaults to the first
     *     day of the current hotel month
     * @param toDate optional inclusive upper bound of the search range; defaults to the current
     *     hotel date
     * @param model model used to render the page
     * @return the Daily Work Record By Staff template
     */
    @GetMapping("/staff/daily-work-record/by-staff")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ATTENDANCE')")
    public String byStaff(
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveFrom = fromDate != null ? fromDate : today.withDayOfMonth(1);
        LocalDate effectiveTo = toDate != null ? toDate : today;

        StaffSearchCriteria everyStaff = new StaffSearchCriteria();
        everyStaff.normalize();
        model.addAttribute("staffOptions", staffService.search(everyStaff));
        model.addAttribute("selectedStaffId", staffId);
        model.addAttribute("fromDate", effectiveFrom);
        model.addAttribute("toDate", effectiveTo);

        if (staffId == null) {
            model.addAttribute("workHistory", List.<DailyWorkRecordHistoryLine>of());
        } else if (effectiveFrom.isAfter(effectiveTo)) {
            model.addAttribute("errorMessage", "From date must not be after To date.");
            model.addAttribute("workHistory", List.<DailyWorkRecordHistoryLine>of());
        } else {
            model.addAttribute("workHistory", dailyWorkRecordService.history(staffId, effectiveFrom, effectiveTo));
        }
        return "staff/daily-work-record-by-staff";
    }

    /**
     * Saves every submitted line for the selected work date as one atomic operation.
     *
     * @param form submitted work date and Staff lines
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @param model model used to redisplay the form after a validation failure
     * @return a redirect back to the same work date on success, or the same template with a
     *     safe error message and the submitted values preserved on failure
     */
    @PostMapping("/staff/daily-work-record")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ATTENDANCE')")
    public String save(
            @ModelAttribute("form") DailyWorkRecordFormRequest form,
            RedirectAttributes redirectAttributes,
            Model model) {
        try {
            dailyWorkRecordService.saveBulk(form.getWorkDate(), form.getLines());
            redirectAttributes.addFlashAttribute("successMessage", "Daily Work Record saved successfully.");
            return "redirect:/staff/daily-work-record?workDate=" + form.getWorkDate();
        } catch (ResponseStatusException exception) {
            dailyWorkRecordService.refreshWorkingTime(form.getLines());
            model.addAttribute("form", form);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "staff/daily-work-record";
        }
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by a Daily Work Record operation
     * @return browser-safe error message
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
