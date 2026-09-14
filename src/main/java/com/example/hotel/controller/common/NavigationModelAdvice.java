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
     * Adds the Room Management navigation flag for the current authenticated user.
     *
     * @param model MVC model used by shared layout fragments
     * @param authentication current user authentication, if available
     */
    @ModelAttribute
    public void addNavigationAttributes(Model model, Authentication authentication) {
        boolean canManageRoom = authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "PERM_MANAGE_ROOM".equals(authority.getAuthority()));
        model.addAttribute("canManageRoom", canManageRoom);
    }
}
