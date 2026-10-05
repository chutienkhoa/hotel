package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the pre-check-in Room reassignment reached from the Check-in Review or from Reservation Detail. It is owned by
 * {@code CHECK_IN}, the permission of the check-in workflow it serves, and delegates every business rule to
 * {@link ReservationRoomReassignmentService}. It never uses the in-stay Room Change flow.
 *
 * <p>The page it returns to is chosen from a fixed, whitelisted context ({@value #RESERVATION_CONTEXT}); any other value
 * means the Check-in Review. A return URL is never accepted from the client.</p>
 */
@Controller
public class RoomReassignmentPageController {

    /** The only accepted {@code from} value: the user opened the form from Reservation Detail. */
    static final String RESERVATION_CONTEXT = "reservation";

    private final ReservationRoomReassignmentService reassignmentService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param reassignmentService owner of the reassignment operation
     * @param messageSource message source for user-facing feedback
     */
    public RoomReassignmentPageController(
            ReservationRoomReassignmentService reassignmentService, MessageSource messageSource) {
        this.reassignmentService = reassignmentService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the replacement form for one assigned room.
     *
     * @param reservationId Reservation identifier
     * @param roomId Room currently assigned, being replaced
     * @param from optional return context; only {@value #RESERVATION_CONTEXT} is recognised
     * @param model model used to render the form
     * @param redirectAttributes feedback when the reservation can no longer be reassigned
     * @return the form template, or a redirect back to where the user came from
     */
    @GetMapping("/check-in/reservations/{reservationId}/rooms/{roomId}/reassign")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String form(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            @RequestParam(required = false) String from,
            Model model,
            RedirectAttributes redirectAttributes) {
        boolean fromReservation = RESERVATION_CONTEXT.equals(from);
        try {
            model.addAttribute("form", reassignmentService.form(reservationId, roomId));
            model.addAttribute("returnToReservation", fromReservation);
            return "check-in/reassign";
        } catch (RoomReassignmentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failure(exception));
            return returnRedirect(reservationId, fromReservation);
        }
    }

    /**
     * Executes the reassignment and returns to where the user came from: Reservation Detail when the form was opened
     * from there, otherwise the Check-in Review, where readiness is recomputed.
     *
     * @param reservationId Reservation identifier
     * @param roomId Room currently assigned, being replaced
     * @param targetRoomId replacement Room
     * @param from optional return context; only {@value #RESERVATION_CONTEXT} is recognised
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to the return page, or back to the form on an eligibility failure
     */
    @PostMapping("/check-in/reservations/{reservationId}/rooms/{roomId}/reassign")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String reassign(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            @RequestParam UUID targetRoomId,
            @RequestParam(required = false) String from,
            RedirectAttributes redirectAttributes) {
        boolean fromReservation = RESERVATION_CONTEXT.equals(from);
        try {
            reassignmentService.reassign(reservationId, roomId, targetRoomId);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("checkin.reassign.success"));
            return returnRedirect(reservationId, fromReservation);
        } catch (RoomReassignmentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failure(exception));
            boolean formStillValid = exception.getReason() == RoomReassignmentException.Reason.ROOM_UNAVAILABLE
                    || exception.getReason() == RoomReassignmentException.Reason.ROOM_ALREADY_ASSIGNED
                    || exception.getReason() == RoomReassignmentException.Reason.INSUFFICIENT_ADULT_CAPACITY
                    || exception.getReason() == RoomReassignmentException.Reason.CAPACITY_NOT_CONFIGURED;
            return formStillValid
                    ? "redirect:/check-in/reservations/" + reservationId + "/rooms/" + roomId + "/reassign"
                            + (fromReservation ? "?from=" + RESERVATION_CONTEXT : "")
                    : returnRedirect(reservationId, fromReservation);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw exception;
            }
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("checkin.reassign.error.NOT_FOUND"));
            return returnRedirect(reservationId, fromReservation);
        }
    }

    private String failure(RoomReassignmentException exception) {
        return messages.get("checkin.reassign.error." + exception.getReason().name());
    }

    private String returnRedirect(UUID reservationId, boolean fromReservation) {
        return fromReservation
                ? "redirect:/reservations/" + reservationId
                : "redirect:/check-in/reservations/" + reservationId;
    }
}
