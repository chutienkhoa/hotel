package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.service.booking.StayExtensionService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the Extend Stay wizard of a CHECKED_IN Reservation: Select (GET), Review (POST, persists nothing) and Confirm
 * (POST, the only mutation). Every business rule is delegated to {@link StayExtensionService}; this controller only
 * chooses the screen and the localized feedback. The Select and Review screens are stateless: the proposed date travels
 * as a query or form parameter, so Back from Review returns with the same date.
 */
@Controller
public class StayExtensionPageController {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Rejections the user can fix on the Select screen; every other rejection returns to Reservation Detail. */
    private static final Set<Reason> RECOVERABLE = Set.of(
            Reason.INVALID_NEW_CHECK_OUT_DATE, Reason.INVENTORY_CONFLICT, Reason.STALE_CHECK_OUT_DATE);

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
     * Displays the Select Extension screen. A proposed date from the query string is previewed (nights, room
     * availability, charge); without one the screen asks for a date.
     *
     * @param id reservation identifier
     * @param newCheckOutDate proposed new check-out date, or {@code null}
     * @param model model used to render the screen
     * @param authentication current authentication (payment data needs MANAGE_PAYMENT; room change needs CHANGE_ROOM)
     * @param redirectAttributes feedback when the reservation is not extendable
     * @return the Select template, or a redirect to the detail page
     */
    @GetMapping("/reservations/{id}/stay-extension")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    public String form(
            @PathVariable UUID id,
            @RequestParam(name = "newCheckOutDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate newCheckOutDate,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            return selectView(id, newCheckOutDate, null, authentication, model);
        } catch (StayExtensionException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", localized(exception));
            return "redirect:/reservations/" + id;
        }
    }

    /**
     * Validates the selected date against the current stay and shows the read-only Review. Nothing is persisted; the
     * Confirm step re-validates under lock.
     *
     * @param id reservation identifier
     * @param form expected current check-out and proposed new check-out
     * @param bindingResult structural validation result
     * @param model model used to render the Review or redisplay the Select screen
     * @param authentication current authentication
     * @param redirectAttributes feedback when the reservation is no longer extendable
     * @return the Review template, or the Select template on a recoverable rejection
     */
    @PostMapping("/reservations/{id}/stay-extension/review")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    public String review(
            @PathVariable UUID id,
            @Valid @ModelAttribute("stayExtensionForm") StayExtensionRequest form,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        try {
            if (bindingResult.hasErrors()) {
                return selectView(id, form.newCheckOutDate(), messages.get("reservation.stayExtension.error.DATE_REQUIRED"),
                        authentication, model);
            }
            model.addAttribute("preview",
                    stayExtensionService.review(id, form, canSeePayments(authentication)));
            return "reservation/stay-extension-review";
        } catch (StayExtensionException exception) {
            return rejected(id, form.newCheckOutDate(), exception, authentication, model, redirectAttributes);
        }
    }

    /**
     * Applies the extension and returns to the detail page (Post/Redirect/Get, so a browser refresh cannot repeat the
     * mutation); a recoverable rejection re-renders the Select screen with the proposed date.
     *
     * @param id reservation identifier
     * @param form submitted expected and new check-out dates
     * @param bindingResult structural validation result
     * @param model model used to redisplay the Select screen
     * @param authentication current authentication
     * @param redirectAttributes post-redirect feedback
     * @return the detail redirect, or the Select template on a recoverable rejection
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
                return selectView(id, form.newCheckOutDate(), messages.get("reservation.stayExtension.error.DATE_REQUIRED"),
                        authentication, model);
            }
            stayExtensionService.extend(id, form);
            redirectAttributes.addFlashAttribute(
                    "successMessage", messages.get("reservation.stayExtension.success", form.newCheckOutDate().format(DATE_FORMAT)));
            return "redirect:/reservations/" + id;
        } catch (StayExtensionException exception) {
            return rejected(id, form.newCheckOutDate(), exception, authentication, model, redirectAttributes);
        }
    }

    /**
     * Serves the primary image of a current room of the Stay, for the read-only Extend Stay screens. It grants no
     * mutation and the file stays in private storage.
     *
     * @param id reservation identifier
     * @param roomId a room currently assigned to the Stay
     * @return the room's primary image with its validated content type and safe inline header
     */
    @GetMapping("/reservations/{id}/stay-extension/rooms/{roomId}/image")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    public ResponseEntity<Resource> roomImage(@PathVariable UUID id, @PathVariable UUID roomId) {
        RoomImageFile image = stayExtensionService.currentRoomImage(id, roomId);
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
     * Renders the Select screen for a proposed date, with an optional localized message.
     *
     * @param id reservation identifier
     * @param requested proposed new check-out date, or {@code null}
     * @param errorMessage localized message to show, or {@code null}
     * @param authentication current authentication
     * @param model model used to render the screen
     * @return the Select template name
     */
    private String selectView(
            UUID id, LocalDate requested, String errorMessage, Authentication authentication, Model model) {
        StayExtensionPreviewResponse preview = stayExtensionService.preview(id, requested, canSeePayments(authentication));
        model.addAttribute("preview", preview);
        model.addAttribute("canChangeRoom", canChangeRoom(authentication));
        if (errorMessage != null) {
            model.addAttribute("errorMessage", errorMessage);
        }
        return "reservation/stay-extension";
    }

    /**
     * Routes a rejected Review or Confirm: a fixable rejection shows the Select screen with its message, anything else
     * returns to Reservation Detail with the message.
     *
     * @param id reservation identifier
     * @param requested the proposed new check-out date that was rejected
     * @param exception the rejection
     * @param authentication current authentication
     * @param model model used to render the Select screen
     * @param redirectAttributes feedback used by the redirect path
     * @return the Select template name, or the detail redirect
     */
    private String rejected(
            UUID id,
            LocalDate requested,
            StayExtensionException exception,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (RECOVERABLE.contains(exception.getExtensionReason())) {
            try {
                return selectView(id, requested, localized(exception), authentication, model);
            } catch (StayExtensionException nested) {
                exception = nested;
            }
        }
        redirectAttributes.addFlashAttribute("errorMessage", localized(exception));
        return "redirect:/reservations/" + id;
    }

    private boolean canSeePayments(Authentication authentication) {
        return hasAuthority(authentication, "PERM_MANAGE_PAYMENT");
    }

    private boolean canChangeRoom(Authentication authentication) {
        return hasAuthority(authentication, "PERM_CHANGE_ROOM");
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private String localized(StayExtensionException exception) {
        return messages.get("reservation.stayExtension.error." + exception.getExtensionReason().name(), exception.getArguments().toArray());
    }
}
