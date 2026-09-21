package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.RolePermissionUpdateRequest;
import com.example.hotel.service.common.RolePermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves the CSRF-protected Roles &amp; Permissions matrix page. */
@Controller
public class RolePermissionPageController {

    private static final String TEMPLATE = "roles-permissions/index";

    private final RolePermissionService rolePermissionService;

    /**
     * Creates the Roles &amp; Permissions page controller.
     *
     * @param rolePermissionService service that loads and saves the permission matrix
     */
    public RolePermissionPageController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    /**
     * Displays the current permission matrix loaded from the database.
     *
     * @param model model used to render the page
     * @return the matrix template
     */
    @GetMapping("/roles-permissions")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String show(Model model) {
        model.addAttribute("matrix", rolePermissionService.loadMatrix());
        return TEMPLATE;
    }

    /**
     * Saves the submitted permission matrix.
     *
     * @param form the untrusted submitted matrix
     * @param model model used to redisplay the page after a failure
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect back to the matrix, or the matrix with a friendly error
     */
    @PostMapping("/roles-permissions")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String save(
            @ModelAttribute("form") RolePermissionUpdateRequest form,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            rolePermissionService.update(form);
            redirectAttributes.addFlashAttribute("successMessage", "Role permissions updated successfully.");
            return "redirect:/roles-permissions";
        } catch (ResponseStatusException exception) {
            model.addAttribute("matrix", rolePermissionService.loadMatrix());
            model.addAttribute(
                    "errorMessage",
                    exception.getReason() == null
                            ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                            : exception.getReason());
            return TEMPLATE;
        }
    }
}
