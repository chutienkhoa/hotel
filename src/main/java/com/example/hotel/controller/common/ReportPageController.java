package com.example.hotel.controller.common;

import java.time.Clock;
import java.time.YearMonth;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Reports Overview: a navigation hub for the reporting area. It loads no report data and
 * performs no calculation; concrete reports are added as separate routes by later tasks.
 */
@Controller
public class ReportPageController {

    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param clock hotel business clock used for the default month of the PDF export form
     */
    public ReportPageController(Clock clock) {
        this.clock = clock;
    }

    /**
     * Displays the Reports Overview to users holding the reporting permission.
     *
     * @param model model receiving the default month for the PDF export form
     * @return the Reports Overview template
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('PERM_VIEW_REPORT')")
    public String overview(Model model) {
        model.addAttribute("selectedMonth", YearMonth.now(clock).toString());
        return "report/overview";
    }
}
