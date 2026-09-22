package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.exception.ReservationFieldUpdateException;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import jakarta.validation.Valid;
import java.util.Set;
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
 * Serves the two controlled Reservation field updates that stay available through CHECKED_IN: Booking Contact and
 * Reservation Notes. Unlike {@link ReservationModificationPageController}'s Change Dates and OTA correction, these
 * two do not require the absence of a Stay; they close only once a Reservation is CHECKED_OUT, CANCELLED or
 * NO_SHOW. No other Reservation field is exposed here.
 */
@Controller
public class ReservationContactAndNotesPageController {

    /** Lifecycle states in which Booking Contact and Reservation Notes remain editable. */
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "CONFIRMED", "CHECKED_IN");

    private final ReservationService reservationService;
    private final ReservationQueryService reservationQueryService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param reservationService owner of both controlled write operations
     * @param reservationQueryService source of current Reservation presentation data
     * @param messageSource localized UI message source
     */
    public ReservationContactAndNotesPageController(
            ReservationService reservationService,
            ReservationQueryService reservationQueryService,
            MessageSource messageSource) {
        this.reservationService = reservationService;
        this.reservationQueryService = reservationQueryService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the Booking Contact form, pre-filled with the Reservation's own stored snapshot (blank when it has
     * none, never silently pre-filled with the Primary Guest fallback shown on Reservation Detail).
     *
     * @param id Reservation identifier
     * @param model view model
     * @param redirectAttributes feedback when the lifecycle boundary is closed
     * @return the Booking Contact form template, or a redirect to Reservation Detail
     */
    @GetMapping("/reservations/{id}/booking-contact")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String bookingContactForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        if (!editable(reservation)) {
            return redirectLocked(reservation, redirectAttributes);
        }
        BookingContactUpdateRequest form = reservation.bookingContactFromPrimaryGuest()
                ? new BookingContactUpdateRequest(null, null, null)
                : new BookingContactUpdateRequest(
                        reservation.effectiveBookingContactName(),
                        reservation.effectiveBookingContactPhone(),
                        reservation.effectiveBookingContactEmail());
        model.addAttribute("reservation", reservation);
        model.addAttribute("bookingContactForm", form);
        return "reservation/booking-contact";
    }

    /**
     * Submits the controlled Booking Contact update and redisplays a recoverable lifecycle failure.
     *
     * @param id Reservation identifier
     * @param form submitted replacement Booking Contact values
     * @param bindingResult structural validation result
     * @param model view model used on redisplay
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to Reservation Detail, or the Booking Contact form on validation failure
     */
    @PostMapping("/reservations/{id}/booking-contact")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String updateBookingContact(
            @PathVariable UUID id,
            @Valid @ModelAttribute("bookingContactForm") BookingContactUpdateRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("reservation", reservationQueryService.findById(id));
            return "reservation/booking-contact";
        }
        try {
            reservationService.updateBookingContact(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.contact.success"));
            return "redirect:/reservations/" + id;
        } catch (ReservationFieldUpdateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", localized(exception));
            return "redirect:/reservations/" + id;
        }
    }

    /**
     * Displays the Reservation Notes form, pre-filled with the current notes.
     *
     * @param id Reservation identifier
     * @param model view model
     * @param redirectAttributes feedback when the lifecycle boundary is closed
     * @return the Notes form template, or a redirect to Reservation Detail
     */
    @GetMapping("/reservations/{id}/notes")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String notesForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        if (!editable(reservation)) {
            return redirectLocked(reservation, redirectAttributes);
        }
        model.addAttribute("reservation", reservation);
        model.addAttribute("notesForm", new NotesUpdateRequest(reservation.notes()));
        return "reservation/notes";
    }

    /**
     * Submits the controlled Reservation Notes update and redisplays a recoverable lifecycle failure.
     *
     * @param id Reservation identifier
     * @param form submitted replacement notes
     * @param bindingResult structural validation result
     * @param model view model used on redisplay
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to Reservation Detail, or the Notes form on validation failure
     */
    @PostMapping("/reservations/{id}/notes")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String updateNotes(
            @PathVariable UUID id,
            @Valid @ModelAttribute("notesForm") NotesUpdateRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("reservation", reservationQueryService.findById(id));
            return "reservation/notes";
        }
        try {
            reservationService.updateReservationNotes(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.notesEdit.success"));
            return "redirect:/reservations/" + id;
        } catch (ReservationFieldUpdateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", localized(exception));
            return "redirect:/reservations/" + id;
        }
    }

    /** Returns whether the read model is within the editable lifecycle window shared by both operations. */
    private boolean editable(ReservationDetailResponse reservation) {
        return EDITABLE_STATUSES.contains(reservation.status());
    }

    /** Redirects a stale form request to Reservation Detail with the approved lifecycle message. */
    private String redirectLocked(ReservationDetailResponse reservation, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(
                "errorMessage", messages.get("reservation.fieldUpdate.error.RESERVATION_LOCKED"));
        return "redirect:/reservations/" + reservation.id();
    }

    /** Resolves the structured service failure through the EN/VI message bundles. */
    private String localized(ReservationFieldUpdateException exception) {
        return messages.get("reservation.fieldUpdate.error." + exception.getFieldUpdateReason().name());
    }
}
