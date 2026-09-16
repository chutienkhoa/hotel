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
     * Adds the Room Management and Expense Management navigation flags for the current
     * authenticated user, shared by layout fragments such as the sidebar.
     *
     * @param model MVC model used by shared layout fragments
     * @param authentication current user authentication, if available
     */
    @ModelAttribute
    public void addNavigationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canManageRoom", hasAuthority(authentication, "PERM_MANAGE_ROOM"));
        model.addAttribute("canManageExpense", hasAuthority(authentication, "PERM_MANAGE_EXPENSE"));
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
