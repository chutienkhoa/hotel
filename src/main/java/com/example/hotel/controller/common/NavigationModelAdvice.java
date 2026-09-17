package com.example.hotel.controller.common;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Supplies shared navigation permission flags to MVC page models. */
@ControllerAdvice(annotations = Controller.class)
public class NavigationModelAdvice {

    /**
     * Adds every navigation permission flag used by shared layout fragments (such as the
     * sidebar) for the current authenticated user, so every MVC page renders a consistent,
     * permission-accurate navigation regardless of which controller served the page.
     *
     * @param model MVC model used by shared layout fragments
     * @param authentication current user authentication, if available
     */
    @ModelAttribute
    public void addNavigationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
        model.addAttribute("canViewBooking", hasAuthority(authentication, "PERM_VIEW_BOOKING"));
        model.addAttribute("canManageBooking", hasAuthority(authentication, "PERM_MANAGE_BOOKING"));
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
        model.addAttribute("canManageRoom", hasAuthority(authentication, "PERM_MANAGE_ROOM"));
        model.addAttribute("canManageExpense", hasAuthority(authentication, "PERM_MANAGE_EXPENSE"));
        model.addAttribute(
                "canManageAdditionalRevenue", hasAuthority(authentication, "PERM_MANAGE_ADDITIONAL_REVENUE"));
        model.addAttribute("canManagePayment", hasAuthority(authentication, "PERM_MANAGE_PAYMENT"));
        model.addAttribute("canCheckIn", hasAuthority(authentication, "PERM_CHECK_IN"));
        model.addAttribute("canCheckOut", hasAuthority(authentication, "PERM_CHECK_OUT"));
    }

    /**
     * Determines whether the current authentication grants one authority.
     *
     * @param authentication current user authentication, if available
     * @param authority required authority
     * @return {@code true} when authenticated and the authority is granted
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }
}
