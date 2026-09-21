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
 * Serves the pre-check-in Room reassignment recovery reached from the Check-in Review. It is owned by
 * {@code CHECK_IN}, the permission of the check-in workflow it recovers, and delegates every business rule to
 * {@link ReservationRoomReassignmentService}. It never uses the in-stay Room Change flow.
 */
@Controller
public class RoomReassignmentPageController {

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
     * @param model model used to render the form
     * @param redirectAttributes feedback when the reservation can no longer be reassigned
     * @return the form template, or a redirect back to the Check-in Review
     */
    @GetMapping("/check-in/reservations/{reservationId}/rooms/{roomId}/reassign")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String form(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("form", reassignmentService.form(reservationId, roomId));
            return "check-in/reassign";
        } catch (RoomReassignmentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failure(exception));
            return reviewRedirect(reservationId);
        }
    }

    /**
     * Executes the reassignment and returns to the Check-in Review, where readiness is recomputed.
     *
     * @param reservationId Reservation identifier
     * @param roomId Room currently assigned, being replaced
     * @param targetRoomId replacement Room
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to the Check-in Review, or back to the form on an eligibility failure
     */
    @PostMapping("/check-in/reservations/{reservationId}/rooms/{roomId}/reassign")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String reassign(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            @RequestParam UUID targetRoomId,
            RedirectAttributes redirectAttributes) {
        try {
            reassignmentService.reassign(reservationId, roomId, targetRoomId);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("checkin.reassign.success"));
            return reviewRedirect(reservationId);
        } catch (RoomReassignmentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failure(exception));
            boolean formStillValid = exception.getReason() == RoomReassignmentException.Reason.ROOM_UNAVAILABLE
                    || exception.getReason() == RoomReassignmentException.Reason.ROOM_ALREADY_ASSIGNED
                    || exception.getReason() == RoomReassignmentException.Reason.INSUFFICIENT_ADULT_CAPACITY
                    || exception.getReason() == RoomReassignmentException.Reason.CAPACITY_NOT_CONFIGURED;
            return formStillValid
                    ? "redirect:/check-in/reservations/" + reservationId + "/rooms/" + roomId + "/reassign"
                    : reviewRedirect(reservationId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw exception;
            }
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("checkin.reassign.error.NOT_FOUND"));
            return reviewRedirect(reservationId);
        }
    }

    private String failure(RoomReassignmentException exception) {
        return messages.get("checkin.reassign.error." + exception.getReason().name());
    }

    private String reviewRedirect(UUID reservationId) {
        return "redirect:/check-in/reservations/" + reservationId;
    }
}
