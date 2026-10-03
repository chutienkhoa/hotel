package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.RoomChangeCandidateResponse;
import com.example.hotel.dto.booking.response.RoomChangeFormResponse;
import com.example.hotel.dto.booking.response.RoomChangeReviewResponse;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.service.booking.RoomChangeService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the Room Change after Check-in flow (select replacement room / reason / notes, read-only
 * Review, atomic Confirm) and delegates every business operation to {@link RoomChangeService},
 * which reuses the existing deterministic Room-locking pattern and never mutates the immutable
 * ReservationRoom booking/pricing snapshot.
 */
@Controller
public class RoomChangePageController {

    private final RoomChangeService roomChangeService;
    private final MessageSource messageSource;

    /**
     * Creates the Room Change MVC controller with its collaborators.
     *
     * @param roomChangeService service implementing the Room Change operational flow
     * @param messageSource resolves the localized post-confirmation feedback
     */
    public RoomChangePageController(RoomChangeService roomChangeService, MessageSource messageSource) {
        this.roomChangeService = roomChangeService;
        this.messageSource = messageSource;
    }

    /**
     * Displays the Room Change form for one currently occupied room, with replacement candidates
     * restricted to the remaining planned occupancy period of that room's specific lineage.
     *
     * @param reservationId Reservation identifier
     * @param roomId the room currently occupied, being replaced
     * @param model model used to render the form
     * @return the Room Change form template name
     */
    @GetMapping("/reservations/{reservationId}/rooms/{roomId}/change")
    @PreAuthorize("hasAuthority('PERM_CHANGE_ROOM')")
    public String form(@PathVariable UUID reservationId, @PathVariable UUID roomId, Model model) {
        addFormAttributes(model, reservationId, roomId, emptyForm());
        return "reservation/room-change";
    }

    /**
     * Validates the submitted selections and displays a read-only Review before the atomic
     * Confirm step. Nothing is persisted by this step.
     *
     * @param reservationId Reservation identifier
     * @param roomId the room currently occupied, being replaced
     * @param roomChangeForm submitted target room, reason, and notes
     * @param bindingResult structural validation result
     * @param model model used to render the Review or redisplay the form after a safe error
     * @return the Room Change Review template, or the form template on validation failure
     */
    @PostMapping("/reservations/{reservationId}/rooms/{roomId}/change/review")
    @PreAuthorize("hasAuthority('PERM_CHANGE_ROOM')")
    public String review(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            @Valid @ModelAttribute("roomChangeForm") RoomChangeRequest roomChangeForm,
            BindingResult bindingResult,
            Model model) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, reservationId, roomId, roomChangeForm);
            return "reservation/room-change";
        }
        try {
            RoomChangeReviewResponse reviewResponse = roomChangeService.review(reservationId, roomId, roomChangeForm);
            model.addAttribute("review", reviewResponse);
            model.addAttribute("roomChangeForm", roomChangeForm);
            return "reservation/room-change-review";
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            addFormAttributes(model, reservationId, roomId, roomChangeForm);
            return "reservation/room-change";
        }
    }

    /**
     * Executes the atomic Room Change confirmation.
     *
     * @param reservationId Reservation identifier
     * @param roomId the room currently occupied, being replaced
     * @param roomChangeForm submitted target room, reason, and notes, resubmitted from Review
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to Reservation Detail on success, or the form template on failure
     */
    @PostMapping("/reservations/{reservationId}/rooms/{roomId}/change/confirm")
    @PreAuthorize("hasAuthority('PERM_CHANGE_ROOM')")
    public String confirm(
            @PathVariable UUID reservationId,
            @PathVariable UUID roomId,
            @Valid @ModelAttribute("roomChangeForm") RoomChangeRequest roomChangeForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, reservationId, roomId, roomChangeForm);
            return "reservation/room-change";
        }
        try {
            // Read the room numbers before the change so the success feedback can name both rooms; the read is
            // read-only and runs the same checks changeRoom repeats under lock.
            RoomChangeReviewResponse summary = roomChangeService.review(reservationId, roomId, roomChangeForm);
            Response response = roomChangeService.changeRoom(reservationId, roomId, roomChangeForm);
            redirectAttributes.addFlashAttribute("successMessage", messageSource.getMessage(
                    "reservation.roomChange.success",
                    new Object[] {summary.currentRoom().roomNumber(), summary.targetRoom().roomNumber()},
                    LocaleContextHolder.getLocale()));
            return "redirect:/reservations/" + response.id();
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            addFormAttributes(model, reservationId, roomId, roomChangeForm);
            return "reservation/room-change";
        }
    }

    /**
     * Serves the primary image of the current room or one current replacement candidate, for display on the Change
     * Room screen only. Read-only: it grants no room mutation, and the file stays in private storage.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId the room currently occupied, being replaced
     * @param roomId the Room whose primary image is requested; must be part of this Change Room workflow
     * @return the Room's primary image with its validated content type and safe inline header
     */
    @GetMapping("/reservations/{reservationId}/rooms/{currentRoomId}/change/images/{roomId}")
    @PreAuthorize("hasAuthority('PERM_CHANGE_ROOM')")
    public ResponseEntity<Resource> roomImage(
            @PathVariable UUID reservationId, @PathVariable UUID currentRoomId, @PathVariable UUID roomId) {
        RoomImageFile image = roomChangeService.changeRoomImage(reservationId, currentRoomId, roomId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(image.originalFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(image.resource());
    }

    /**
     * Adds the Room Change form's data: the current room context, replacement-room candidates, and approved
     * reasons.
     *
     * @param model model used to render the form
     * @param reservationId Reservation identifier
     * @param roomId the room currently occupied, being replaced
     * @param roomChangeForm form data to preserve after a validation error
     */
    private void addFormAttributes(Model model, UUID reservationId, UUID roomId, RoomChangeRequest roomChangeForm) {
        model.addAttribute("reservationId", reservationId);
        model.addAttribute("roomId", roomId);
        model.addAttribute("roomChangeForm", roomChangeForm);
        model.addAttribute("reasons", RoomChangeReason.values());
        RoomChangeFormResponse currentRoom = null;
        List<RoomChangeCandidateResponse> candidateRooms;
        try {
            currentRoom = roomChangeService.formView(reservationId, roomId);
            // Candidates are listed only while the date window is open (fail closed without the form context);
            // a closed window shows a blocked state instead. The service still re-checks every operation.
            candidateRooms = currentRoom != null && currentRoom.roomChangeOpen()
                    ? roomChangeService.candidateRooms(reservationId, roomId)
                    : List.of();
        } catch (ResponseStatusException exception) {
            candidateRooms = List.of();
            model.addAttribute("errorMessage", safeMessage(exception));
        }
        model.addAttribute("currentRoom", currentRoom);
        model.addAttribute("candidateRooms", candidateRooms);
    }

    /**
     * Creates the initial empty Room Change form model.
     *
     * @return the initial Room Change form model
     */
    private RoomChangeRequest emptyForm() {
        return new RoomChangeRequest(null, null, null);
    }

    /**
     * Selects a user-safe message from a known service exception.
     *
     * @param exception exception raised by a Room Change operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }
}
