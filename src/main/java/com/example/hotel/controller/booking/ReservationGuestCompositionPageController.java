package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.exception.GuestCompositionUpdateException;
import com.example.hotel.exception.GuestCompositionUpdateException.Reason;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.customer.GuestQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the dedicated "Edit Guest Composition" operation for a CONFIRMED Reservation (adults, children and
 * Accompanying Guests only). It is owned by {@code MANAGE_BOOKING}, exposes no other Reservation field, and delegates
 * every rule to {@link ReservationService#updateConfirmedGuestComposition}. It never edits the Primary Guest.
 */
@Controller
public class ReservationGuestCompositionPageController {

    private final ReservationService reservationService;
    private final ReservationQueryService reservationQueryService;
    private final GuestQueryService guestQueryService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param reservationService owner of the update operation
     * @param reservationQueryService reads the current composition
     * @param guestQueryService supplies the existing Guest profiles for the picker
     * @param messageSource message source for localized feedback
     */
    public ReservationGuestCompositionPageController(
            ReservationService reservationService,
            ReservationQueryService reservationQueryService,
            GuestQueryService guestQueryService,
            MessageSource messageSource) {
        this.reservationService = reservationService;
        this.reservationQueryService = reservationQueryService;
        this.guestQueryService = guestQueryService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the form prefilled with the stored composition, only for a CONFIRMED Reservation.
     *
     * @param id reservation identifier
     * @param model model used to render the form
     * @param redirectAttributes feedback when the reservation is not eligible
     * @return the form template, or a redirect to the detail page
     */
    @GetMapping("/reservations/{id}/guest-composition")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String form(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        if (!"CONFIRMED".equals(reservation.status())) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("reservation.composition.error.RESERVATION_NOT_CONFIRMED"));
            return "redirect:/reservations/" + id;
        }
        GuestCompositionUpdateRequest form = new GuestCompositionUpdateRequest(
                reservation.adultCount(),
                reservation.childCount(),
                reservation.accompanyingGuests().stream().map(AccompanyingGuestResponse::guestId).toList());
        addFormAttributes(model, reservation, form);
        return "reservation/guest-composition";
    }

    /**
     * Applies the update and returns to the detail page; a validation or business rejection re-renders the form.
     *
     * @param id reservation identifier
     * @param form submitted adults, children and accompanying guest ids
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form
     * @param redirectAttributes post-redirect feedback
     * @return the detail redirect, or the form on a recoverable rejection
     */
    @PostMapping("/reservations/{id}/guest-composition")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("guestCompositionForm") GuestCompositionUpdateRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, reservationQueryService.findById(id), form);
            return "reservation/guest-composition";
        }
        try {
            reservationService.updateConfirmedGuestComposition(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.composition.success"));
            return "redirect:/reservations/" + id;
        } catch (GuestCompositionUpdateException exception) {
            String message = localized(exception);
            if (exception.getUpdateReason() == Reason.RESERVATION_NOT_CONFIRMED
                    || exception.getUpdateReason() == Reason.STAY_ALREADY_EXISTS) {
                redirectAttributes.addFlashAttribute("errorMessage", message);
                return "redirect:/reservations/" + id;
            }
            model.addAttribute("errorMessage", message);
            addFormAttributes(model, reservationQueryService.findById(id), form);
            return "reservation/guest-composition";
        }
    }

    private void addFormAttributes(Model model, ReservationDetailResponse reservation, GuestCompositionUpdateRequest form) {
        model.addAttribute("reservation", reservation);
        model.addAttribute("guestCompositionForm", form);
        model.addAttribute("guests", guestQueryService.findAllForReservationCreation());
        model.addAttribute("selectedAccompanyingGuests", guestQueryService.findAllByIds(form.accompanyingGuestIdsOrEmpty()));
    }

    private String localized(GuestCompositionUpdateException exception) {
        Object[] arguments = exception.getArguments().toArray();
        return switch (exception.getUpdateReason()) {
            case INSUFFICIENT_ADULT_CAPACITY -> messages.get("checkin.readiness.issue.INSUFFICIENT_ADULT_CAPACITY", arguments);
            case CAPACITY_NOT_CONFIGURED -> messages.get("checkin.readiness.issue.CAPACITY_NOT_CONFIGURED", arguments);
            case INVALID_ADULT_COUNT -> messages.get("validation.reservation.adultCount.min");
            case INVALID_CHILD_COUNT -> messages.get("validation.reservation.childCount.min");
            case DUPLICATE_GUEST -> messages.get("reservation.guests.alreadySelected");
            case PRIMARY_GUEST_AS_ACCOMPANYING -> messages.get("reservation.guests.primaryNotAllowed");
            default -> messages.get("reservation.composition.error." + exception.getUpdateReason().name());
        };
    }
}
