package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.ConfirmedReservationModificationException;
import com.example.hotel.exception.ConfirmedReservationModificationException.Reason;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayQueryService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
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
 * Serves the two narrow pre-check-in modifications of a CONFIRMED Reservation: changing dates and correcting an OTA
 * reference. No generic confirmed-reservation edit surface is exposed.
 */
@Controller
public class ReservationModificationPageController {

    private final ReservationService reservationService;
    private final ReservationQueryService reservationQueryService;
    private final StayQueryService stayQueryService;
    private final UiMessages messages;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param reservationService owner of both controlled write operations
     * @param reservationQueryService source of current Reservation presentation data
     * @param stayQueryService source used to enforce the form-level pre-check-in boundary
     * @param messageSource localized UI message source
     * @param clock authoritative hotel Clock used for the date input minimum
     */
    public ReservationModificationPageController(
            ReservationService reservationService,
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            MessageSource messageSource,
            Clock clock) {
        this.reservationService = reservationService;
        this.reservationQueryService = reservationQueryService;
        this.stayQueryService = stayQueryService;
        this.messages = new UiMessages(messageSource);
        this.clock = clock;
    }

    /**
     * Displays the dedicated date-change form when the Reservation is still eligible.
     *
     * @param id Reservation identifier
     * @param model view model
     * @param redirectAttributes feedback when the lifecycle boundary is closed
     * @return date-change template or Reservation-detail redirect
     */
    @GetMapping("/reservations/{id}/change-dates")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String changeDatesForm(
            @PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        if (!eligible(reservation)) {
            return redirectIneligible(reservation, redirectAttributes);
        }
        ReservationDateChangeRequest form = new ReservationDateChangeRequest(
                reservation.checkInDate(), reservation.checkOutDate());
        addDateFormAttributes(model, reservation, form);
        return "reservation/change-dates";
    }

    /**
     * Submits the controlled date change and redisplays recoverable validation/business failures.
     *
     * @param id Reservation identifier
     * @param form submitted replacement dates
     * @param bindingResult structural validation result
     * @param model view model used on redisplay
     * @param redirectAttributes post-redirect feedback
     * @return Reservation-detail redirect or date-change template
     */
    @PostMapping("/reservations/{id}/change-dates")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String changeDates(
            @PathVariable UUID id,
            @Valid @ModelAttribute("dateChangeForm") ReservationDateChangeRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addDateFormAttributes(model, reservationQueryService.findById(id), form);
            return "reservation/change-dates";
        }
        try {
            reservationService.changeConfirmedDates(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.changeDates.success"));
            return "redirect:/reservations/" + id;
        } catch (ConfirmedReservationModificationException exception) {
            return handleDateFailure(id, form, exception, model, redirectAttributes);
        }
    }

    /**
     * Displays the dedicated OTA-reference correction form when the Reservation is still eligible and non-DIRECT.
     *
     * @param id Reservation identifier
     * @param model view model
     * @param redirectAttributes feedback when the operation is unavailable
     * @return correction template or Reservation-detail redirect
     */
    @GetMapping("/reservations/{id}/correct-ota-reference")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String correctOtaReferenceForm(
            @PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        if (!eligible(reservation)) {
            return redirectIneligible(reservation, redirectAttributes);
        }
        if (reservation.source() == BookingSource.DIRECT) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", messages.get("reservation.modification.error.DIRECT_RESERVATION"));
            return "redirect:/reservations/" + id;
        }
        model.addAttribute("reservation", reservation);
        model.addAttribute("otaReferenceForm", new OtaReferenceCorrectionRequest(reservation.otaBookingReference()));
        return "reservation/correct-ota-reference";
    }

    /**
     * Submits the controlled OTA-reference correction and redisplays recoverable validation failures.
     *
     * @param id Reservation identifier
     * @param form submitted corrected reference
     * @param bindingResult structural validation result
     * @param model view model used on redisplay
     * @param redirectAttributes post-redirect feedback
     * @return Reservation-detail redirect or correction template
     */
    @PostMapping("/reservations/{id}/correct-ota-reference")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String correctOtaReference(
            @PathVariable UUID id,
            @Valid @ModelAttribute("otaReferenceForm") OtaReferenceCorrectionRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("reservation", reservationQueryService.findById(id));
            return "reservation/correct-ota-reference";
        }
        try {
            reservationService.correctOtaBookingReference(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.otaReference.success"));
            return "redirect:/reservations/" + id;
        } catch (ConfirmedReservationModificationException exception) {
            String message = localized(exception);
            if (isLifecycleFailure(exception) || exception.getModificationReason() == Reason.DIRECT_RESERVATION) {
                redirectAttributes.addFlashAttribute("errorMessage", message);
                return "redirect:/reservations/" + id;
            }
            model.addAttribute("reservation", reservationQueryService.findById(id));
            model.addAttribute("errorMessage", message);
            return "reservation/correct-ota-reference";
        }
    }

    /** Adds common data for the date-change form. */
    private void addDateFormAttributes(
            Model model, ReservationDetailResponse reservation, ReservationDateChangeRequest form) {
        model.addAttribute("reservation", reservation);
        model.addAttribute("dateChangeForm", form);
        model.addAttribute("hotelToday", LocalDate.now(clock));
    }

    /** Handles a structured date-change rejection without exposing financial amounts. */
    private String handleDateFailure(
            UUID id,
            ReservationDateChangeRequest form,
            ConfirmedReservationModificationException exception,
            Model model,
            RedirectAttributes redirectAttributes) {
        String message = localized(exception);
        if (isLifecycleFailure(exception)) {
            redirectAttributes.addFlashAttribute("errorMessage", message);
            return "redirect:/reservations/" + id;
        }
        addDateFormAttributes(model, reservationQueryService.findById(id), form);
        model.addAttribute("errorMessage", message);
        return "reservation/change-dates";
    }

    /** Returns whether the read model is within the same pre-check-in lifecycle boundary as the services. */
    private boolean eligible(ReservationDetailResponse reservation) {
        return "CONFIRMED".equals(reservation.status())
                && stayQueryService.findByReservationId(reservation.id()) == null;
    }

    /** Redirects a stale form request to detail with the approved lifecycle message. */
    private String redirectIneligible(
            ReservationDetailResponse reservation, RedirectAttributes redirectAttributes) {
        String reason = "CONFIRMED".equals(reservation.status())
                ? "STAY_ALREADY_EXISTS"
                : "RESERVATION_NOT_CONFIRMED";
        redirectAttributes.addFlashAttribute(
                "errorMessage", messages.get("reservation.modification.error." + reason));
        return "redirect:/reservations/" + reservation.id();
    }

    /** Returns whether a service failure means the form is no longer eligible. */
    private boolean isLifecycleFailure(ConfirmedReservationModificationException exception) {
        return exception.getModificationReason() == Reason.RESERVATION_NOT_CONFIRMED
                || exception.getModificationReason() == Reason.STAY_ALREADY_EXISTS;
    }

    /** Resolves the structured service failure through the EN/VI message bundles. */
    private String localized(ConfirmedReservationModificationException exception) {
        return messages.get(
                "reservation.modification.error." + exception.getModificationReason().name(),
                exception.getArguments().toArray());
    }
}
