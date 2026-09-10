package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * Serves reservation Thymeleaf pages and delegates all reservation operations to existing services.
 */
@Controller
public class ReservationPageController {

    private final ReservationQueryService reservationQueryService;
    private final ReservationService reservationService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;

    /**
     * Creates the MVC controller with query services for presentation data and the reservation
     * service for existing write operations.
     *
     * @param reservationQueryService service used to load reservation views
     * @param reservationService service used to execute existing reservation operations
     * @param guestQueryService service used to load guest choices
     * @param roomQueryService service used to load room choices
     */
    public ReservationPageController(
            ReservationQueryService reservationQueryService,
            ReservationService reservationService,
            GuestQueryService guestQueryService,
            RoomQueryService roomQueryService) {
        this.reservationQueryService = reservationQueryService;
        this.reservationService = reservationService;
        this.guestQueryService = guestQueryService;
        this.roomQueryService = roomQueryService;
    }

    /**
     * Displays reservations available to users with reservation-view permission.
     *
     * @param model model used to render the list view
     * @param authentication current browser authentication
     * @return the reservation list template name
     */
    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('PERM_VIEW_BOOKING')")
    public String list(Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("reservations", reservationQueryService.findAll());
        return "reservation/list";
    }

    /**
     * Displays one reservation and only the state actions valid for its current status.
     *
     * @param id reservation identifier
     * @param model model used to render the detail view
     * @param authentication current browser authentication
     * @return the reservation detail template name
     */
    @GetMapping("/reservations/{id}")
    @PreAuthorize("hasAuthority('PERM_VIEW_BOOKING')")
    public String detail(@PathVariable UUID id, Model model, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("reservation", reservationQueryService.findById(id));
        return "reservation/detail";
    }

    /**
     * Displays the reservation creation form with read-only guest and room choices.
     *
     * @param model model used to render the creation form
     * @param authentication current browser authentication
     * @return the reservation form template name
     */
    @GetMapping("/reservations/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String createForm(Model model, Authentication authentication) {
        addReservationFormAttributes(model, emptyReservationForm(), authentication);
        return "reservation/form";
    }

    /**
     * Submits the existing reservation-create operation through a CSRF-protected session form.
     *
     * @param reservationForm request fields bound from the form
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to detail on success or the form template on validation failure
     */
    @PostMapping("/reservations")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String create(
            @Valid @ModelAttribute("reservationForm") CreateRequest reservationForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addReservationFormAttributes(model, reservationForm, authentication);
            return "reservation/form";
        }
        try {
            Response response = reservationService.create(reservationForm);
            redirectAttributes.addFlashAttribute("successMessage", "Reservation created successfully.");
            return "redirect:/reservations/" + response.id();
        } catch (ResponseStatusException exception) {
            addReservationFormAttributes(model, reservationForm, authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "reservation/form";
        }
    }

    /**
     * Confirms a draft reservation through the existing reservation service operation.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/confirm")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String confirm(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return redirectAfterAction(id, redirectAttributes, "Reservation confirmed successfully.",
                () -> reservationService.confirm(id));
    }

    /**
     * Cancels a confirmed reservation through the existing reservation service operation.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String cancel(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return redirectAfterAction(id, redirectAttributes, "Reservation cancelled successfully.",
                () -> reservationService.cancel(id));
    }

    /**
     * Marks a confirmed reservation as no-show through the existing service operation.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/no-show")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String noShow(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return redirectAfterAction(id, redirectAttributes, "Reservation marked as no-show.",
                () -> reservationService.noShow(id));
    }

    /**
     * Checks in a confirmed reservation through the existing reservation service operation.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/check-in")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String checkIn(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return redirectAfterAction(id, redirectAttributes, "Check-in completed successfully.",
                () -> reservationService.checkIn(id));
    }

    /**
     * Adds lookup and authorization data required to render the reservation creation form.
     *
     * @param model model used to render the form
     * @param reservationForm form data to preserve after validation errors
     * @param authentication current browser authentication
     */
    private void addReservationFormAttributes(
            Model model, CreateRequest reservationForm, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("reservationForm", reservationForm);
        model.addAttribute("guests", guestQueryService.findAllForReservationCreation());
        model.addAttribute("rooms", roomQueryService.findAllForReservationCreation());
    }

    /**
     * Adds the permission flags used by templates to present only authorized actions.
     *
     * @param model model used to render a page
     * @param authentication current browser authentication
     */
    private void addAuthorizationAttributes(Model model, Authentication authentication) {
        model.addAttribute("canManageBooking", hasAuthority(authentication, "PERM_MANAGE_BOOKING"));
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
        model.addAttribute("canCheckIn", hasAuthority(authentication, "PERM_CHECK_IN"));
    }

    /**
     * Determines whether the current authentication includes the specified backend authority.
     *
     * @param authentication current browser authentication
     * @param authority required backend authority
     * @return {@code true} when the authority is present
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    /**
     * Creates the initial request model with one empty room row for browser form binding.
     *
     * @return the initial reservation form model
     */
    private CreateRequest emptyReservationForm() {
        return new CreateRequest(null, null, null, null, null, List.of(new RoomRequest(null, null)));
    }

    /**
     * Redirects after an existing state operation and exposes only a safe backend error message.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @param successMessage message displayed when the operation succeeds
     * @param action existing service operation to execute
     * @return a redirect to the reservation detail page
     */
    private String redirectAfterAction(
            UUID id,
            RedirectAttributes redirectAttributes,
            String successMessage,
            ReservationAction action) {
        try {
            action.execute();
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return "redirect:/reservations/" + id;
    }

    /**
     * Selects a user-safe message from a known HTTP business exception.
     *
     * @param exception exception raised by an existing service operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }

    /**
     * Represents an existing reservation service operation invoked by an MVC action.
     */
    @FunctionalInterface
    private interface ReservationAction {

        /**
         * Executes the delegated reservation operation.
         */
        void execute();
    }
}
