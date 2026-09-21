package com.example.hotel.controller.common;

import jakarta.servlet.http.HttpServletRequest;
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
     * @param request current request, used to keep the URL when switching language
     */
    @ModelAttribute
    public void addNavigationAttributes(Model model, Authentication authentication, HttpServletRequest request) {
        model.addAttribute("languageSwitchBase", languageSwitchBase(request));
        model.addAttribute("currentUsername", authentication == null ? null : authentication.getName());
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
        model.addAttribute("canManageStaff", hasAuthority(authentication, "PERM_MANAGE_STAFF"));
        model.addAttribute("canManageAttendance", hasAuthority(authentication, "PERM_MANAGE_ATTENDANCE"));
        model.addAttribute("canManageUser", hasAuthority(authentication, "PERM_MANAGE_USER"));
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

    /**
     * Builds the current URL, from its query string only (never request body parameters), without
     * the {@code lang} parameter, ending in {@code ?} or {@code &} so a language link can append
     * {@code lang=vi|en}. For non-GET requests it is the safe fallback {@code /reservations?}.
     *
     * @param request current request
     * @return the language-switch link base
     */
    private String languageSwitchBase(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            // A page rendered by a POST must never be re-requested with GET at the POST URL (it may not
            // exist). A controller can supply an exact GET target through the languageSwitchPath model
            // attribute; otherwise fall back to the shell's home route, which every role can open.
            return "/reservations?";
        }
        String query = request.getQueryString();
        StringBuilder kept = new StringBuilder();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (!pair.isEmpty() && !pair.equals("lang") && !pair.startsWith("lang=")) {
                    kept.append(pair).append('&');
                }
            }
        }
        return request.getRequestURI() + "?" + kept;
    }
}
