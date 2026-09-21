package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.StayExtensionFormResponse;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.service.booking.StayExtensionService;
import jakarta.validation.Valid;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the dedicated "Extend Stay" operation of a CHECKED_IN Reservation. It is owned by {@code EXTEND_STAY},
 * accepts only the expected current check-out date and the new check-out date, and delegates every rule to
 * {@link StayExtensionService}.
 */
@Controller
public class StayExtensionPageController {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StayExtensionService stayExtensionService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param stayExtensionService owner of the extension operation
     * @param messageSource message source for localized feedback
     */
    public StayExtensionPageController(StayExtensionService stayExtensionService, MessageSource messageSource) {
        this.stayExtensionService = stayExtensionService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the form for a CHECKED_IN Reservation.
     *
     * @param id reservation identifier
     * @param model model used to render the form
     * @param authentication current authentication (payment data is shown only with MANAGE_PAYMENT)
     * @param redirectAttributes feedback when the reservation is not extendable
     * @return the form template, or a redirect to the detail page
     */
    @GetMapping("/reservations/{id}/stay-extension")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    public String form(
            @PathVariable UUID id, Model model, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            StayExtensionFormResponse context = stayExtensionService.form(id, canSeePayments(authentication));
            model.addAttribute("context", context);
            model.addAttribute(
                    "stayExtensionForm", new StayExtensionRequest(context.currentCheckOutDate(), context.earliestNewCheckOutDate()));
            return "reservation/stay-extension";
        } catch (StayExtensionException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", localized(exception));
            return "redirect:/reservations/" + id;
        }
    }

    /**
     * Applies the extension and returns to the detail page; a recoverable rejection re-renders the form.
     *
     * @param id reservation identifier
     * @param form submitted expected and new check-out dates
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form
     * @param authentication current authentication
     * @param redirectAttributes post-redirect feedback
     * @return the detail redirect, or the form on a recoverable rejection
     */
    @PostMapping("/reservations/{id}/stay-extension")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    public String extend(
            @PathVariable UUID id,
            @Valid @ModelAttribute("stayExtensionForm") StayExtensionRequest form,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            if (bindingResult.hasErrors()) {
                model.addAttribute("context", stayExtensionService.form(id, canSeePayments(authentication)));
                return "reservation/stay-extension";
            }
            stayExtensionService.extend(id, form);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("reservation.stayExtension.success", form.newCheckOutDate().format(DATE_FORMAT)));
            return "redirect:/reservations/" + id;
        } catch (StayExtensionException exception) {
            String message = localized(exception);
            Reason reason = exception.getExtensionReason();
            if (reason == Reason.INVALID_NEW_CHECK_OUT_DATE
                    || reason == Reason.INVENTORY_CONFLICT
                    || reason == Reason.STALE_CHECK_OUT_DATE) {
                model.addAttribute("errorMessage", message);
                model.addAttribute("context", stayExtensionService.form(id, canSeePayments(authentication)));
                return "reservation/stay-extension";
            }
            redirectAttributes.addFlashAttribute("errorMessage", message);
            return "redirect:/reservations/" + id;
        }
    }

    private boolean canSeePayments(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(a -> "PERM_MANAGE_PAYMENT".equals(a.getAuthority()));
    }

    private String localized(StayExtensionException exception) {
        return messages.get("reservation.stayExtension.error." + exception.getExtensionReason().name(), exception.getArguments().toArray());
    }
}
