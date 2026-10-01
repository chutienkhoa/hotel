package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.UserCreateRequest;
import com.example.hotel.dto.common.request.UserPasswordResetRequest;
import com.example.hotel.dto.common.request.UserSearchCriteria;
import com.example.hotel.dto.common.request.UserUpdateRequest;
import com.example.hotel.dto.common.response.UserResponse;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import com.example.hotel.service.common.UserService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves CSRF-protected Thymeleaf pages for PMS User Account Management. */
@Controller
public class UserPageController {

    private static final String FORM_TEMPLATE = "users/form";
    private static final String RESET_TEMPLATE = "users/reset-password";

    private final UserService userService;

    /**
     * Creates the User page controller.
     *
     * @param userService service used to load and mutate user accounts
     */
    public UserPageController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Displays user accounts matching the optional username and status filters.
     *
     * @param searchCriteria submitted filters
     * @param model model used to render the page
     * @return the user list template
     */
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String list(@ModelAttribute("searchCriteria") UserSearchCriteria searchCriteria, Model model) {
        searchCriteria.normalize();
        model.addAttribute("users", userService.search(searchCriteria));
        return "users/list";
    }

    /**
     * Displays an empty user creation form.
     *
     * @param model model used to render the form
     * @return the user form template
     */
    @GetMapping("/users/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String createForm(Model model) {
        model.addAttribute("userForm", new UserCreateRequest(null, null, null, null, null));
        addCreateOptions(model);
        return FORM_TEMPLATE;
    }

    /**
     * Creates a user account through a CSRF-protected form.
     *
     * @param userForm submitted account data
     * @param model model used to redisplay the form after a failure
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a list redirect, or the form template with a friendly error
     */
    @PostMapping("/users")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String create(
            @ModelAttribute("userForm") UserCreateRequest userForm, Model model, RedirectAttributes redirectAttributes) {
        try {
            UserResponse user = userService.create(userForm);
            redirectAttributes.addFlashAttribute("successMessage", "User " + user.username() + " created successfully.");
            return "redirect:/users";
        } catch (ResponseStatusException exception) {
            model.addAttribute(
                    "userForm",
                    new UserCreateRequest(userForm.staffId(), userForm.username(), null, null, userForm.role()));
            model.addAttribute("errorMessage", safeMessage(exception));
            addCreateOptions(model);
            return FORM_TEMPLATE;
        }
    }

    /**
     * Displays one user account with the actions available to the current administrator.
     *
     * @param id user account identifier
     * @param authentication the current administrator
     * @param model model used to render the page
     * @return the user detail template
     */
    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String detail(@PathVariable UUID id, Authentication authentication, Model model) {
        UserResponse user = userService.findById(id);
        model.addAttribute("user", user);
        model.addAttribute("isSelf", id.equals(currentUserId(authentication)));
        return "users/detail";
    }

    /**
     * Displays the editable fields of an account: Staff link and role. The username is read-only.
     *
     * @param id user account identifier
     * @param authentication the current administrator
     * @param model model used to render the form
     * @return the user form template
     */
    @GetMapping("/users/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String editForm(@PathVariable UUID id, Authentication authentication, Model model) {
        UserResponse user = userService.findById(id);
        model.addAttribute("userForm", new UserUpdateRequest(user.staffId(), singleRole(user)));
        addEditOptions(model, user, authentication);
        return FORM_TEMPLATE;
    }

    /**
     * Updates the Staff link and role of an account.
     *
     * @param id user account identifier
     * @param userForm submitted replacement values
     * @param authentication the current administrator
     * @param model model used to redisplay the form after a failure
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect, or the form template with a friendly error
     */
    @PostMapping("/users/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String update(
            @PathVariable UUID id,
            @ModelAttribute("userForm") UserUpdateRequest userForm,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            UserResponse user = userService.update(id, userForm);
            redirectAttributes.addFlashAttribute("successMessage", "User " + user.username() + " updated successfully.");
            return "redirect:/users/" + id;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw exception;
            }
            addEditOptions(model, userService.findById(id), authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return FORM_TEMPLATE;
        }
    }

    /**
     * Displays the administrator password reset form.
     *
     * @param id user account identifier
     * @param model model used to render the form
     * @return the reset password template
     */
    @GetMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String resetPasswordForm(@PathVariable UUID id, Model model) {
        model.addAttribute("user", userService.findById(id));
        model.addAttribute("resetForm", new UserPasswordResetRequest(null, null));
        return RESET_TEMPLATE;
    }

    /**
     * Resets an account's password through a CSRF-protected form.
     *
     * @param id user account identifier
     * @param resetForm submitted replacement password and confirmation
     * @param model model used to redisplay the form after a failure
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a detail redirect, or the reset template with a friendly error
     */
    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String resetPassword(
            @PathVariable UUID id,
            @ModelAttribute("resetForm") UserPasswordResetRequest resetForm,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            userService.resetPassword(id, resetForm);
            redirectAttributes.addFlashAttribute("successMessage", "Password reset successfully.");
            return "redirect:/users/" + id;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw exception;
            }
            model.addAttribute("user", userService.findById(id));
            model.addAttribute("resetForm", new UserPasswordResetRequest(null, null));
            model.addAttribute("errorMessage", safeMessage(exception));
            return RESET_TEMPLATE;
        }
    }

    /**
     * Activates an inactive account from a CSRF-protected form.
     *
     * @param id user account identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the account detail
     */
    @PostMapping("/users/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String activate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            UserResponse user = userService.activate(id);
            redirectAttributes.addFlashAttribute("successMessage", "User " + user.username() + " activated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/users/" + id;
    }

    /**
     * Deactivates an active account from a CSRF-protected form.
     *
     * @param id user account identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return a redirect to the account detail
     */
    @PostMapping("/users/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_MANAGE_USER')")
    public String deactivate(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            UserResponse user = userService.deactivate(id);
            redirectAttributes.addFlashAttribute(
                    "successMessage", "User " + user.username() + " deactivated successfully.");
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/users/" + id;
    }

    /**
     * Adds the Staff and role options for the create form.
     *
     * @param model model used to render the form
     */
    private void addCreateOptions(Model model) {
        model.addAttribute("staffOptions", userService.linkableStaff(null));
        model.addAttribute("roleOptions", UserService.ASSIGNABLE_ROLES);
    }

    /**
     * Adds the account, Staff options, role options, and self flag for the edit form.
     *
     * @param model model used to render the form
     * @param user the account being edited
     * @param authentication the current administrator
     */
    private void addEditOptions(Model model, UserResponse user, Authentication authentication) {
        model.addAttribute("user", user);
        model.addAttribute("isSelf", user.id().equals(currentUserId(authentication)));
        model.addAttribute("staffOptions", userService.linkableStaff(user.id()));
        model.addAttribute("roleOptions", UserService.ASSIGNABLE_ROLES);
    }

    /**
     * Returns the account's role when it holds exactly one, for preselecting the edit form.
     *
     * @param user the account
     * @return the single role code, or {@code null} when none or several are assigned
     */
    private String singleRole(UserResponse user) {
        return user.role() == null || user.role().isBlank() || user.role().contains(",") ? null : user.role();
    }

    /**
     * Resolves the identifier of the authenticated administrator, if it is an application user.
     *
     * @param authentication the current authentication
     * @return the user identifier, or {@code null} when unavailable
     */
    private UUID currentUserId(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return sessionUserPrincipal.id();
        }
        if (principal instanceof CurrentUser currentUser) {
            return currentUser.id();
        }
        return null;
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by a user operation
     * @return browser-safe error message
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
