package com.example.hotel.controller.common;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Reports Overview: a navigation hub for the reporting area. It loads no report data and
 * performs no calculation; concrete reports are added as separate routes by later tasks.
 */
@Controller
public class ReportPageController {

    /**
     * Displays the Reports Overview to users holding the reporting permission.
     *
     * @return the Reports Overview template
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('PERM_VIEW_REPORT')")
    public String overview() {
        return "report/overview";
    }
}
