package com.example.hotel.controller.common;

import com.example.hotel.service.common.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the read-only Dashboard v1 Thymeleaf page. */
@Controller
public class DashboardPageController {

    private final DashboardService dashboardService;

    /**
     * Creates the Dashboard MVC controller.
     *
     * @param dashboardService service used to load approved Dashboard metrics
     */
    public DashboardPageController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Sends the application root to the Dashboard. Authentication and authorization are still
     * enforced by the security chain and by the Dashboard route itself.
     *
     * @return redirect to the Dashboard route
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    /**
     * Displays approved Dashboard v1 metrics to users with reporting permission.
     *
     * @param model model used to render the Dashboard
     * @param authentication current browser authentication
     * @return Dashboard template name
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERM_VIEW_REPORT')")
    public String dashboard(Model model, Authentication authentication) {
        model.addAttribute("dashboard", dashboardService.getDashboard());
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
        return "dashboard/index";
    }

    /**
     * Determines whether the current authentication contains one authority.
     *
     * @param authentication current browser authentication
     * @param authority required authority
     * @return {@code true} when the authority is present
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }
}
