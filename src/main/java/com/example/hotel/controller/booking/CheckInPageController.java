package com.example.hotel.controller.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the V1 Check-in operational pages (landing, Existing Reservation search/review, OTA
 * Booking Not Entered, Walk-in) and delegates every business operation to {@link CheckInService},
 * which itself reuses the existing Reservation lifecycle without introducing an alternate path.
 */
@Controller
@RequestMapping("/check-in")
public class CheckInPageController {

    private final CheckInService checkInService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;

    /**
     * Creates the Check-in MVC controller with its collaborators.
     *
     * @param checkInService service implementing the Check-in operational flows
     * @param guestQueryService service used to load eligible Guest choices
     * @param roomQueryService service used to load Room choices for OTA Booking Not Entered
     */
    public CheckInPageController(
            CheckInService checkInService, GuestQueryService guestQueryService, RoomQueryService roomQueryService) {
        this.checkInService = checkInService;
        this.guestQueryService = guestQueryService;
        this.roomQueryService = roomQueryService;
    }

    /**
     * Displays the Check-in landing page with its three operational entry flows.
     *
     * @return the Check-in landing template name
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String landing() {
        return "check-in/landing";
    }

    /**
     * Displays a local-database-only search for CONFIRMED Reservations to check in.
     *
     * @param searchCriteria submitted Reservation Number / Guest / OTA Booking Reference filters
     * @param page zero-based requested page number
     * @param model model used to render the search page
     * @return the Existing Reservation search template name
     */
    @GetMapping("/existing")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String existingSearch(
            @ModelAttribute("searchCriteria") ReservationSearchCriteria searchCriteria,
            @RequestParam(required = false) String page,
            Model model) {
        searchCriteria.normalizeReservationNumber();
        searchCriteria.normalizeGuest();
        searchCriteria.normalizeOtaBookingReference();
        int requestedPage = PaginationSupport.parsePage(page);
        Page<?> reservationPage = checkInService.searchConfirmedReservations(searchCriteria, requestedPage);
        String sortKey = TableSorts.CHECK_IN.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.CHECK_IN.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, "reservationNumber", searchCriteria.getReservationNumber());
        putIfPresent(filters, "guest", searchCriteria.getGuest());
        putIfPresent(filters, "otaBookingReference", searchCriteria.getOtaBookingReference());
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                reservationPage, requestedPage, "/check-in/existing", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("reservationPage", reservationPage);
        PaginationSupport.populate(model, reservationPage, "/check-in/existing", filters, sortKey, sortDir);
        return "check-in/existing";
    }

    /**
     * Displays the read-only Check-in Review for one Reservation.
     *
     * @param id Reservation identifier
     * @param model model used to render the Review page
     * @param authentication current browser authentication
     * @return the Check-in Review template name
     */
    @GetMapping("/reservations/{id}")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String review(@PathVariable UUID id, Model model, Authentication authentication) {
        CheckInReviewResponse review = checkInService.review(id);
        model.addAttribute("review", review);
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
        return "check-in/review";
    }

    /**
     * Submits the explicit human confirmation for an existing Reservation's Check-in. The Early
     * check-in rule is re-evaluated independently inside the service regardless of what the
     * Review page displayed, so a direct POST cannot bypass it.
     *
     * @param id Reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to Reservation Detail on success, or back to Review on failure
     */
    @PostMapping("/reservations/{id}/confirm")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String confirm(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            checkInService.confirmCheckIn(id);
            redirectAttributes.addFlashAttribute("successMessage", "Guest checked in successfully.");
            return "redirect:/reservations/" + id;
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
            return "redirect:/check-in/reservations/" + id;
        }
    }

    /**
     * Displays the OTA Booking Not Entered form.
     *
     * @param model model used to render the form
     * @return the OTA entry template name
     */
    @GetMapping("/ota-entry")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntryForm(Model model) {
        addOtaEntryFormAttributes(model, emptyOtaEntryForm());
        return "check-in/ota-entry";
    }

    /**
     * Creates and confirms an OTA Reservation, then continues directly into Check-in Review.
     *
     * @param otaEntryForm submitted OTA Reservation data
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect into Check-in Review on success, or the form template on failure
     */
    @PostMapping("/ota-entry")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntrySubmit(
            @Valid @ModelAttribute("otaEntryForm") CreateRequest otaEntryForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addOtaEntryFormAttributes(model, otaEntryForm);
            return "check-in/ota-entry";
        }
        try {
            Response response = checkInService.createOtaEntry(otaEntryForm);
            redirectAttributes.addFlashAttribute(
                    "successMessage", "Reservation created and confirmed. Continue with Check-in review below.");
            return "redirect:/check-in/reservations/" + response.id();
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            addOtaEntryFormAttributes(model, otaEntryForm);
            return "check-in/ota-entry";
        }
    }

    /**
     * Displays the Walk-in form. Source is always DIRECT and is never exposed for selection.
     *
     * @param model model used to render the form
     * @return the Walk-in template name
     */
    @GetMapping("/walk-in")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInForm(Model model) {
        addWalkInFormAttributes(model, emptyWalkInForm(), List.of());
        return "check-in/walk-in";
    }

    /**
     * Returns active Rooms available for the entire Walk-in date range (hotel current date
     * through the requested check-out date), reused by the Walk-in form's client-side room
     * selector. This list is UX guidance only; final confirmation independently re-validates.
     *
     * @param checkOutDate requested check-out date
     * @return date-range-available Room choices
     */
    @GetMapping("/walk-in/available-rooms")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    @ResponseBody
    public List<RoomLookupResponse> availableRooms(
            @RequestParam("checkOutDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        return checkInService.availableRoomsForWalkIn(checkOutDate);
    }

    /**
     * Validates the submitted Walk-in selections and displays a read-only Review before the
     * atomic "Confirm &amp; Check-in" operation. Nothing is persisted by this step.
     *
     * @param walkInForm submitted Walk-in guest/room/date selections
     * @param bindingResult structural validation result
     * @param model model used to render the Review or redisplay the form after a safe error
     * @return the Walk-in Review template, or the form template on validation failure
     */
    @PostMapping("/walk-in/review")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInReview(
            @Valid @ModelAttribute("walkInForm") WalkInRequest walkInForm, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            addWalkInFormAttributes(model, walkInForm, List.of());
            return "check-in/walk-in";
        }
        try {
            WalkInReviewResponse review = checkInService.reviewWalkIn(walkInForm);
            model.addAttribute("review", review);
            model.addAttribute("walkInForm", walkInForm);
            return "check-in/walk-in-review";
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            addWalkInFormAttributes(model, walkInForm, List.of());
            return "check-in/walk-in";
        }
    }

    /**
     * Executes the atomic Walk-in "Confirm &amp; Check-in" operation.
     *
     * @param walkInForm submitted Walk-in guest/room/date selections, resubmitted from Review
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to Reservation Detail on success, or the Walk-in form on failure
     */
    @PostMapping("/walk-in/confirm")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInConfirm(
            @Valid @ModelAttribute("walkInForm") WalkInRequest walkInForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addWalkInFormAttributes(model, walkInForm, List.of());
            return "check-in/walk-in";
        }
        try {
            Response response = checkInService.confirmWalkIn(walkInForm);
            redirectAttributes.addFlashAttribute("successMessage", "Walk-in guest checked in successfully.");
            return "redirect:/reservations/" + response.id();
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", safeMessage(exception));
            addWalkInFormAttributes(model, walkInForm, List.of());
            return "check-in/walk-in";
        }
    }

    /**
     * Adds the OTA Booking Not Entered form's lookup data, restricted to OTA sources only.
     *
     * @param model model used to render the form
     * @param otaEntryForm form data to preserve after a validation error
     */
    private void addOtaEntryFormAttributes(Model model, CreateRequest otaEntryForm) {
        model.addAttribute("otaEntryForm", otaEntryForm);
        model.addAttribute("guests", guestQueryService.findAllForReservationCreation());
        model.addAttribute("rooms", roomQueryService.findAllForReservationCreation());
        model.addAttribute(
                "otaSources", Arrays.stream(BookingSource.values()).filter(source -> source != BookingSource.DIRECT).toList());
    }

    /**
     * Adds the Walk-in form's lookup data.
     *
     * @param model model used to render the form
     * @param walkInForm form data to preserve after a validation error
     * @param assignedRooms Room choices to retain alongside the eligible-for-range list
     */
    private void addWalkInFormAttributes(Model model, WalkInRequest walkInForm, List<RoomLookupResponse> assignedRooms) {
        model.addAttribute("walkInForm", walkInForm);
        model.addAttribute("guests", guestQueryService.findAllForReservationCreation());
        model.addAttribute("rooms", assignedRooms);
    }

    /**
     * Creates the initial empty OTA entry form model with one empty room row for form binding.
     *
     * @return the initial OTA entry form model
     */
    private CreateRequest emptyOtaEntryForm() {
        return new CreateRequest(null, null, null, Reservation.DEFAULT_ADULT_COUNT, Reservation.DEFAULT_CHILD_COUNT,
                null, null, null, null, List.of(new RoomRequest(null, null)), List.of());
    }

    /**
     * Creates the initial empty Walk-in form model with one empty room row for form binding.
     *
     * @return the initial Walk-in form model
     */
    private WalkInRequest emptyWalkInForm() {
        return new WalkInRequest(null, null, Reservation.DEFAULT_ADULT_COUNT, Reservation.DEFAULT_CHILD_COUNT,
                null, null, List.of(new RoomRequest(null, null)));
    }

    private void putIfPresent(Map<String, String> filters, String name, String value) {
        if (value != null) {
            filters.put(name, value);
        }
    }

    /**
     * Selects a user-safe message from a known service exception.
     *
     * @param exception exception raised by a Check-in operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
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
}
