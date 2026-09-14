package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final StayQueryService stayQueryService;
    private final StayBalanceService stayBalanceService;

    /**
     * Creates the MVC controller with query services for presentation data and the reservation
     * service for existing write operations.
     *
     * @param reservationQueryService service used to load reservation views
     * @param reservationService service used to execute existing reservation operations
     * @param guestQueryService service used to load guest choices
     * @param roomQueryService service used to load room choices
     * @param stayQueryService service used to resolve a Reservation's Stay
     * @param stayBalanceService service used to supply non-financial checkout readiness
     */
    public ReservationPageController(
            ReservationQueryService reservationQueryService,
            ReservationService reservationService,
            GuestQueryService guestQueryService,
            RoomQueryService roomQueryService,
            StayQueryService stayQueryService,
            StayBalanceService stayBalanceService) {
        this.reservationQueryService = reservationQueryService;
        this.reservationService = reservationService;
        this.guestQueryService = guestQueryService;
        this.roomQueryService = roomQueryService;
        this.stayQueryService = stayQueryService;
        this.stayBalanceService = stayBalanceService;
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
    public String list(
            @ModelAttribute("searchCriteria") ReservationSearchCriteria searchCriteria,
            BindingResult bindingResult,
            @RequestParam(required = false) Integer page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        searchCriteria.normalizeReservationNumber();
        model.addAttribute("reservationStatuses", ReservationStatus.values());

        String validationMessage = validateSearchCriteria(searchCriteria, bindingResult);
        if (validationMessage != null) {
            model.addAttribute("errorMessage", validationMessage);
            Page<?> reservationPage = Page.empty();
            model.addAttribute("reservationPage", reservationPage);
            addPaginationAttributes(model, reservationPage);
            return "reservation/list";
        }

        Page<?> reservationPage = reservationQueryService.findPage(searchCriteria, page == null ? 0 : page);
        model.addAttribute("reservationPage", reservationPage);
        addPaginationAttributes(model, reservationPage);
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
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        model.addAttribute("reservation", reservation);
        addCheckoutReadiness(model, reservation, authentication);
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
     * Checks out an eligible reservation through the existing transactional reservation service operation.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/check-out")
    @PreAuthorize("hasAuthority('PERM_CHECK_OUT')")
    public String checkOut(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        return redirectAfterAction(id, redirectAttributes, "Check-out completed successfully.",
                () -> reservationService.checkOut(id));
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
        model.addAttribute("canCheckOut", hasAuthority(authentication, "PERM_CHECK_OUT"));
        model.addAttribute("canManagePayment", hasAuthority(authentication, "PERM_MANAGE_PAYMENT"));
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
    }

    /**
     * Validates structural binding and the independent one-calendar-year limits for list filters.
     *
     * @param criteria submitted list filters
     * @param bindingResult binding result for the submitted filters
     * @return a user-safe validation message, or {@code null} when criteria are valid
     */
    private String validateSearchCriteria(
            ReservationSearchCriteria criteria, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "Please provide valid reservation filter values.";
        }
        String checkInError = validateDateRange(
                criteria.getCheckInFrom(), criteria.getCheckInTo(), "Check-in");
        if (checkInError != null) {
            return checkInError;
        }
        return validateDateRange(criteria.getCheckOutFrom(), criteria.getCheckOutTo(), "Check-out");
    }

    /**
     * Adds presentation-only page-window bounds for the Reservation list paginator.
     *
     * @param model MVC model used by the Reservation list view
     * @param reservationPage current server-side page metadata
     */
    private void addPaginationAttributes(Model model, Page<?> reservationPage) {
        int totalPages = reservationPage.getTotalPages();
        if (totalPages == 0) {
            return;
        }
        int lastPage = totalPages - 1;
        int startPage = Math.max(0, Math.min(reservationPage.getNumber() - 1, lastPage - 2));
        int endPage = Math.min(lastPage, startPage + 2);
        model.addAttribute("paginationStartPage", startPage);
        model.addAttribute("paginationEndPage", endPage);
    }

    /**
     * Validates one inclusive LocalDate range without applying a database query.
     *
     * @param from optional inclusive lower bound
     * @param to optional inclusive upper bound
     * @param label user-facing date-range label
     * @return a user-safe validation message, or {@code null} when valid
     */
    private String validateDateRange(LocalDate from, LocalDate to, String label) {
        if (from == null || to == null) {
            return null;
        }
        if (to.isBefore(from)) {
            return label + " end date must be on or after the start date.";
        }
        if (to.isAfter(from.plusYears(1))) {
            return label + " date range cannot exceed one calendar year.";
        }
        return null;
    }

    /**
     * Adds the approved non-financial checkout-readiness state for a checked-in Reservation.
     *
     * @param model model used to render Reservation detail
     * @param reservation Reservation detail data
     * @param authentication current browser authentication
     */
    private void addCheckoutReadiness(
            Model model, ReservationDetailResponse reservation, Authentication authentication) {
        if (!"CHECKED_IN".equals(reservation.status())
                || !hasAuthority(authentication, "PERM_CHECK_OUT")) {
            return;
        }
        StayResponse stay = stayQueryService.findByReservationId(reservation.id());
        StayBalance balance = stayBalanceService.calculate(stay.id());
        model.addAttribute(
                "checkoutReadiness",
                balance.outstanding().signum() == 0 ? "READY" : "PAYMENT_REQUIRED");
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
