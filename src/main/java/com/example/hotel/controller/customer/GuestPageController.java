package com.example.hotel.controller.customer;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.service.customer.GuestService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves CSRF-protected Thymeleaf pages for authorized guest management. */
@Controller
public class GuestPageController {

    private final GuestService guestService;

    /**
     * Creates the guest page controller with the guest-management service.
     *
     * @param guestService service used to load and update guest data
     */
    public GuestPageController(GuestService guestService) {
        this.guestService = guestService;
    }

    /**
     * Displays all guest profiles available to guest-management users.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer list template
     */
    @GetMapping("/guests")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String list(Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("guests", guestService.findAll());
        return "customer/list";
    }

    /**
     * Displays one guest profile.
     *
     * @param id guest identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer detail template
     */
    @GetMapping("/guests/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String detail(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("guest", guestService.findById(id));
        return "customer/detail";
    }

    /**
     * Displays an empty guest creation form.
     *
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer form template
     */
    @GetMapping("/guests/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String createForm(Model model, Authentication authentication) {
        addFormAttributes(model, new GuestCreateRequest(null, null, null, null, null, null, null), authentication);
        return "customer/form";
    }

    /**
     * Creates a guest from a browser form and redirects to its detail page after success.
     *
     * @param guestForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/guests")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String create(
            @Valid @ModelAttribute("guestForm") GuestCreateRequest guestForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication);
            return "customer/form";
        }
        try {
            GuestResponse guest = guestService.create(guestForm);
            redirectAttributes.addFlashAttribute("successMessage", "Guest created successfully.");
            return "redirect:/guests/" + guest.id();
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "customer/form";
        }
    }

    /**
     * Displays a pre-populated guest update form.
     *
     * @param id guest identifier
     * @param model model used to render the page
     * @param authentication current browser authentication
     * @return the customer form template
     */
    @GetMapping("/guests/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String updateForm(@PathVariable UUID id, Model model, Authentication authentication) {
        GuestResponse guest = guestService.findById(id);
        addFormAttributes(model, toUpdateRequest(guest), authentication);
        model.addAttribute("guest", guest);
        return "customer/form";
    }

    /**
     * Updates a guest from a browser form and redirects to its detail page after success.
     *
     * @param id guest identifier
     * @param guestForm validated form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a detail redirect or the form template after validation failure
     */
    @PostMapping("/guests/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("guestForm") GuestUpdateRequest guestForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, guestForm, authentication);
            model.addAttribute("guest", guestService.findById(id));
            return "customer/form";
        }
        try {
            guestService.update(id, guestForm);
            redirectAttributes.addFlashAttribute("successMessage", "Guest updated successfully.");
            return "redirect:/guests/" + id;
        } catch (ResponseStatusException exception) {
            addFormAttributes(model, guestForm, authentication);
            model.addAttribute("guest", guestService.findById(id));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "customer/form";
        }
    }

    /**
     * Adds the guest-management permission flag used by the shared sidebar.
     *
     * @param model model used to render a page
     * @param authentication current browser authentication
     */
    private void addAuthorizationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canManageBooking", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_MANAGE_BOOKING".equals(authority.getAuthority())));
        model.addAttribute("canManageGuest", authentication.getAuthorities().stream()
                .anyMatch(authority -> "PERM_MANAGE_GUEST".equals(authority.getAuthority())));
    }

    /**
     * Adds form data and shared authorization attributes needed by the guest form template.
     *
     * @param model model used to render a page
     * @param guestForm create or update form data
     * @param authentication current browser authentication
     */
    private void addFormAttributes(Model model, Object guestForm, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("guestForm", guestForm);
    }

    /**
     * Converts a guest response into the mutable fields accepted by the update form.
     *
     * @param guest guest profile to populate the form
     * @return mutable guest form data without the immutable code
     */
    private GuestUpdateRequest toUpdateRequest(GuestResponse guest) {
        return new GuestUpdateRequest(
                guest.firstName(),
                guest.lastName(),
                guest.email(),
                guest.phone(),
                guest.nationality(),
                guest.dateOfBirth(),
                guest.address());
    }

    /**
     * Selects a browser-safe message from a known service exception.
     *
     * @param exception exception raised by a guest operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
