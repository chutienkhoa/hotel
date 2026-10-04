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
import com.example.hotel.dto.booking.response.WalkInRoomOption;
import com.example.hotel.exception.WalkInReviewException;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.HttpSession;
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

    /**
     * Session key holding the in-progress Walk-in form (plain request data, never an entity). It is written when the
     * form is submitted to the Summary or sent to Create New Guest, and read WITHOUT being consumed by the Walk-in form
     * and the Summary, so browser Back, Forward and Refresh never lose it. It is removed only when the Walk-in
     * completes, or when the user returns to the Check-in Guest landing (the flow was abandoned), so it cannot leak
     * into a genuinely new Walk-in. The OTA Booking Not Entered flow uses its own separate key.
     */
    private static final String WALK_IN_WIZARD_SESSION_KEY = "checkIn.walkIn.stashedForm";

    /** Session key holding one stashed, possibly incomplete, OTA Booking Not Entered form. */
    private static final String OTA_ENTRY_WIZARD_SESSION_KEY = "checkIn.otaEntry.stashedForm";

    /** The Walk-in flow's own {@code returnTo} target, as accepted by {@code GuestPageController}. */
    private static final String WALK_IN_RETURN_TARGET = "/check-in/walk-in";

    /** The OTA Booking Not Entered flow's own {@code returnTo} target, as accepted by {@code GuestPageController}. */
    private static final String OTA_ENTRY_RETURN_TARGET = "/check-in/ota-entry";

    /** Number of rows shown in the landing's Recent Confirmed Reservations table. */
    private static final int RECENT_ARRIVALS_LIMIT = 5;

    private final com.example.hotel.service.booking.PrepaymentService prepaymentService;
    private final CheckInService checkInService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;
    private final FrontDeskQueryService frontDeskQueryService;
    private final com.example.hotel.service.room.RoomImageService roomImageService;
    private final jakarta.validation.Validator validator;

    /**
     * Creates the Check-in MVC controller with its collaborators.
     *
     * @param checkInService service implementing the Check-in operational flows
     * @param guestQueryService service used to load eligible Guest choices
     * @param roomQueryService service used to load Room choices for OTA Booking Not Entered
     * @param prepaymentService read-only prepayment summary shown on the Review
     * @param frontDeskQueryService read model for the landing's Recent Confirmed Reservations
     * @param roomImageService read model for the primary Room image shown on the Walk-in Summary
     * @param validator Bean Validation, used to re-check the prepared Walk-in form before the Summary is shown
     */
    public CheckInPageController(
            CheckInService checkInService,
            GuestQueryService guestQueryService,
            RoomQueryService roomQueryService,
            com.example.hotel.service.booking.PrepaymentService prepaymentService,
            FrontDeskQueryService frontDeskQueryService,
            com.example.hotel.service.room.RoomImageService roomImageService,
            jakarta.validation.Validator validator) {
        this.validator = validator;
        this.roomImageService = roomImageService;
        this.frontDeskQueryService = frontDeskQueryService;
        this.prepaymentService = prepaymentService;
        this.checkInService = checkInService;
        this.guestQueryService = guestQueryService;
        this.roomQueryService = roomQueryService;
    }

    /**
     * Displays the Check-in Guest landing page: its three operational entry flows and the first few pending
     * arrivals (the Front Desk Arrivals read model, so readiness and overdue rules are not duplicated here).
     *
     * @param model model used to render the landing page
     * @param session HTTP session; returning to the landing abandons any in-progress Walk-in
     * @return the Check-in landing template name
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String landing(HttpSession session, Model model) {
        session.removeAttribute(WALK_IN_WIZARD_SESSION_KEY);
        model.addAttribute("recentArrivals", frontDeskQueryService.recentArrivals(RECENT_ARRIVALS_LIMIT));
        model.addAttribute("hotelToday", frontDeskQueryService.hotelToday());
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
        // Payment details are shown only to users who already hold MANAGE_PAYMENT; the Review stays read-only.
        model.addAttribute("prepaymentSummary",
                hasAuthority(authentication, "PERM_MANAGE_PAYMENT") ? prepaymentService.summary(id) : null);
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
    public String otaEntryForm(HttpSession session, Model model) {
        CreateRequest otaEntryForm = withCreatedGuestIfAny(consumeStashedOtaEntryForm(session), model);
        addOtaEntryFormAttributes(model, otaEntryForm);
        return "check-in/ota-entry";
    }

    /**
     * Stashes the OTA Booking Not Entered form's current, possibly incomplete, field values for
     * exactly one subsequent Guest-creation round trip, then redirects to the existing Guest
     * creation form. No Reservation or Guest is created by this step (spec 9.2.5): it only
     * preserves already-entered OTA fields so they are not lost while the user visits Guest
     * creation and returns.
     *
     * @param otaEntryForm current, possibly incomplete, OTA Booking Not Entered field values
     * @param session HTTP session used to carry the stashed values across the Guest-creation round trip
     * @return a redirect to the existing Guest creation form with the OTA entry {@code returnTo} target
     */
    @PostMapping("/ota-entry/new-guest")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntryNewGuest(@ModelAttribute("otaEntryForm") CreateRequest otaEntryForm, HttpSession session) {
        session.setAttribute(OTA_ENTRY_WIZARD_SESSION_KEY, otaEntryForm);
        return "redirect:/guests/new?returnTo=" + OTA_ENTRY_RETURN_TARGET;
    }

    /**
     * Creates and confirms an OTA Reservation, then continues directly into Check-in Review.
     *
     * @param otaEntryForm submitted OTA Reservation data
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param session HTTP session cleared of any leftover stashed OTA entry state on success
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect into Check-in Review on success, or the form template on failure
     */
    @PostMapping("/ota-entry")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntrySubmit(
            @Valid @ModelAttribute("otaEntryForm") CreateRequest otaEntryForm,
            BindingResult bindingResult,
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addOtaEntryFormAttributes(model, otaEntryForm);
            return "check-in/ota-entry";
        }
        try {
            Response response = checkInService.createOtaEntry(otaEntryForm);
            // The wizard has completed, so any stashed in-progress state from an earlier Create New
            // Guest round trip is no longer needed and must not leak into a later, unrelated visit.
            session.removeAttribute(OTA_ENTRY_WIZARD_SESSION_KEY);
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
     * <p>When the user is returning from the existing Guest creation form (spec 9.2.5), this
     * restores the rest of the in-progress Walk-in selections stashed by {@link #walkInNewGuest}
     * and pre-selects the newly created Guest from the {@code createdGuestId} flash attribute that
     * {@code GuestPageController.create} already sets. An absent or invalid stash/{@code
     * createdGuestId} safely falls back to the ordinary empty form.</p>
     *
     * @param session HTTP session possibly holding the in-progress Walk-in form
     * @param model model used to render the form
     * @return the Walk-in template name
     */
    @GetMapping("/walk-in")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInForm(HttpSession session, Model model) {
        // A form flashed by a redirected validation failure carries its own errors; otherwise the in-progress form
        // comes from the session, or the form is empty.
        WalkInRequest walkInForm = model.asMap().get("walkInForm") instanceof WalkInRequest flashed
                ? flashed
                : inProgressWalkInForm(session);
        addWalkInFormAttributes(model, withCreatedGuestIfAny(walkInForm, model), List.of());
        return "check-in/walk-in";
    }

    /**
     * Keeps the Walk-in form's current, possibly incomplete, field values in the session for the
     * Guest-creation round trip, then redirects to the existing Guest creation form. No
     * Reservation or Guest is created by this step (spec 9.2.5): it only preserves already-entered
     * Walk-in fields so they are not lost while the user visits Guest creation and returns.
     *
     * @param walkInForm current, possibly incomplete, Walk-in field values
     * @param session HTTP session used to carry the stashed values across the Guest-creation round trip
     * @return a redirect to the existing Guest creation form with the Walk-in {@code returnTo} target
     */
    @PostMapping("/walk-in/new-guest")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInNewGuest(@ModelAttribute("walkInForm") WalkInRequest walkInForm, HttpSession session) {
        session.setAttribute(WALK_IN_WIZARD_SESSION_KEY, walkInForm);
        return "redirect:/guests/new?returnTo=" + WALK_IN_RETURN_TARGET;
    }

    /**
     * Returns the check-in-ready Rooms available for the entire Walk-in date range (hotel current date
     * through the requested check-out date) with their Room Type and adult capacity, shown by the Walk-in
     * form's Room Selection table. This list is UX guidance only; review and final confirmation
     * independently re-validate.
     *
     * @param checkOutDate requested check-out date
     * @return date-range-available Room choices
     */
    @GetMapping("/walk-in/available-rooms")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    @ResponseBody
    public List<WalkInRoomOption> availableRooms(
            @RequestParam("checkOutDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        return checkInService.walkInRoomOptions(checkOutDate);
    }

    /**
     * Validates the submitted Walk-in selections and prepares the Reservation Summary. Nothing is persisted. A valid
     * form is kept in the session and the browser is redirected to the Summary (POST/Redirect/GET), so the Summary is
     * a normal GET page in the browser history; an invalid form is redirected back to the Walk-in form with its
     * errors, never rendered from this POST.
     *
     * @param walkInForm submitted Walk-in guest/room/date selections
     * @param bindingResult structural validation result
     * @param session HTTP session that keeps the prepared form
     * @param redirectAttributes carries a rejected form and its errors across the redirect
     * @return a redirect to the Summary, or to the Walk-in form on a validation failure
     */
    @PostMapping("/walk-in/review")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInReview(
            @Valid @ModelAttribute("walkInForm") WalkInRequest walkInForm,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return redirectToWalkInForm(walkInForm, bindingResult, null, session, redirectAttributes);
        }
        try {
            checkInService.reviewWalkIn(walkInForm);
        } catch (WalkInReviewException exception) {
            rejectWalkInSelection(bindingResult, exception);
            return redirectToWalkInForm(walkInForm, bindingResult, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectToWalkInForm(walkInForm, bindingResult, safeMessage(exception), session, redirectAttributes);
        }
        session.setAttribute(WALK_IN_WIZARD_SESSION_KEY, walkInForm);
        return "redirect:/check-in/walk-in/review";
    }

    /**
     * Displays the read-only Walk-in Reservation Summary for the form prepared in the session. It is a plain GET
     * resource: refreshing it, or arriving at it by browser Back/Forward, repeats nothing and creates nothing, and it
     * does not clear the prepared form. When no valid prepared form exists (direct URL, expired session, completed
     * Walk-in) or it is no longer valid, the user is sent back to the Walk-in form instead of seeing an error.
     *
     * @param session HTTP session holding the prepared form
     * @param model model used to render the Summary
     * @param authentication current browser authentication
     * @param redirectAttributes carries the notice or errors to the Walk-in form when the Summary cannot be shown
     * @return the Summary template, or a redirect to the Walk-in form
     */
    @GetMapping("/walk-in/review")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInSummary(
            HttpSession session, Model model, Authentication authentication, RedirectAttributes redirectAttributes) {
        if (!(session.getAttribute(WALK_IN_WIZARD_SESSION_KEY) instanceof WalkInRequest walkInForm)
                || !validator.validate(walkInForm).isEmpty()) {
            redirectAttributes.addFlashAttribute("walkInNotice", "checkin.walkIn.summary.missing");
            return "redirect:/check-in/walk-in";
        }
        BindingResult errors = new org.springframework.validation.DirectFieldBindingResult(walkInForm, "walkInForm");
        try {
            WalkInReviewResponse review = checkInService.reviewWalkIn(walkInForm);
            model.addAttribute("review", review);
            model.addAttribute("walkInForm", walkInForm);
            addWalkInSummaryAttributes(model, review, authentication);
            return "check-in/walk-in-review";
        } catch (WalkInReviewException exception) {
            rejectWalkInSelection(errors, exception);
            return redirectToWalkInForm(walkInForm, errors, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectToWalkInForm(walkInForm, errors, safeMessage(exception), session, redirectAttributes);
        }
    }

    /**
     * Executes the atomic Walk-in "Confirm &amp; Check-in" operation.
     *
     * @param walkInForm submitted Walk-in guest/room/date selections, resubmitted from Review
     * @param bindingResult structural validation result
     * @param model model used to redisplay the form after a safe error
     * @param session HTTP session cleared of any leftover stashed Walk-in state on success
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to Reservation Detail on success, or the Walk-in form on failure
     */
    @PostMapping("/walk-in/confirm")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String walkInConfirm(
            @Valid @ModelAttribute("walkInForm") WalkInRequest walkInForm,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return redirectToWalkInForm(walkInForm, bindingResult, null, session, redirectAttributes);
        }
        try {
            // The same read-only rules as the Review, so a selection that went stale after the Summary was shown is
            // reported next to its field; confirmWalkIn then re-validates authoritatively under the room locks.
            checkInService.reviewWalkIn(walkInForm);
            Response response = checkInService.confirmWalkIn(walkInForm);
            // The Walk-in has completed: the in-progress form is removed so it cannot leak into a later Walk-in.
            session.removeAttribute(WALK_IN_WIZARD_SESSION_KEY);
            redirectAttributes.addFlashAttribute("successMessage", "Walk-in guest checked in successfully.");
            return "redirect:/reservations/" + response.id();
        } catch (WalkInReviewException exception) {
            rejectWalkInSelection(bindingResult, exception);
            return redirectToWalkInForm(walkInForm, bindingResult, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectToWalkInForm(walkInForm, bindingResult, safeMessage(exception), session, redirectAttributes);
        }
    }

    /**
     * Redirects a rejected Walk-in form back to the Walk-in page (POST/Redirect/GET) so that no history entry is a
     * rendered POST. The form is kept in the session and flashed with its errors, so the page shows the entered values
     * and the messages next to their fields.
     *
     * @param walkInForm the rejected form
     * @param bindingResult its validation errors
     * @param errorMessage optional general error text
     * @param session HTTP session that keeps the in-progress form
     * @param redirectAttributes flash carrier
     * @return the redirect to the Walk-in form
     */
    private String redirectToWalkInForm(
            WalkInRequest walkInForm,
            BindingResult bindingResult,
            String errorMessage,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        session.setAttribute(WALK_IN_WIZARD_SESSION_KEY, walkInForm);
        redirectAttributes.addFlashAttribute("walkInForm", walkInForm);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "walkInForm", bindingResult);
        if (errorMessage != null) {
            redirectAttributes.addFlashAttribute("errorMessage", errorMessage);
        }
        boolean rateError = bindingResult.getFieldErrors().stream()
                .anyMatch(error -> error.getField().endsWith(".nightlyRate"));
        boolean otherError = bindingResult.getAllErrors().stream()
                .anyMatch(error -> !(error instanceof org.springframework.validation.FieldError fieldError
                        && fieldError.getField().endsWith(".nightlyRate")));
        redirectAttributes.addFlashAttribute("walkInRateError", rateError);
        redirectAttributes.addFlashAttribute("walkInRateErrorOnly", rateError && !otherError);
        return "redirect:/check-in/walk-in";
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
     * Adds the read-only presentation data of the Walk-in Summary that the review response does not carry: the
     * selected Guest's contact and identity fields, and each selected Room's primary image. A Room image is only
     * offered to users who may open Room Detail, because its file endpoint requires that same permission.
     *
     * @param model model used to render the Summary
     * @param review Summary review response
     * @param authentication current browser authentication
     */
    private void addWalkInSummaryAttributes(Model model, WalkInReviewResponse review, Authentication authentication) {
        model.addAttribute("summaryGuest", guestQueryService.findForReservationCreation(review.guestId()));
        Map<UUID, UUID> primaryImageIds = new java.util.HashMap<>();
        if (hasAuthority(authentication, "PERM_MANAGE_ROOM")) {
            review.rooms().forEach(room -> roomImageService.findByRoomId(room.roomId()).stream()
                    .filter(com.example.hotel.dto.room.response.RoomImageResponse::primary)
                    .findFirst()
                    .ifPresent(image -> primaryImageIds.put(room.roomId(), image.id())));
        }
        model.addAttribute("roomImageIds", primaryImageIds);
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
        model.addAttribute("hotelToday", frontDeskQueryService.hotelToday());
    }

    /**
     * Reports a rejected Walk-in selection on the field it concerns, with a localized message resolved from
     * {@code checkin.walkIn.error.<REASON>}.
     *
     * @param bindingResult binding result of the redisplayed Walk-in form
     * @param exception the rejection raised by the Walk-in review
     */
    private void rejectWalkInSelection(BindingResult bindingResult, WalkInReviewException exception) {
        String field =
                switch (exception.getReason()) {
                    case CHECK_OUT_NOT_AFTER_CHECK_IN -> "checkOutDate";
                    case INSUFFICIENT_ADULT_CAPACITY, CAPACITY_NOT_CONFIGURED -> "adultCount";
                    case DUPLICATE_ROOM, ROOM_UNAVAILABLE -> "rooms";
                };
        bindingResult.rejectValue(
                field, "checkin.walkIn.error." + exception.getReason().name(), exception.getArguments(),
                exception.getMessage());
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
                "VND", null, List.of(new RoomRequest(null, null)));
    }

    /**
     * Returns the in-progress Walk-in form from the session WITHOUT removing it, so Back, Forward and Refresh keep it.
     *
     * @param session current HTTP session
     * @return the in-progress Walk-in form, or a fresh empty form when there is none
     */
    private WalkInRequest inProgressWalkInForm(HttpSession session) {
        return session.getAttribute(WALK_IN_WIZARD_SESSION_KEY) instanceof WalkInRequest form ? form : emptyWalkInForm();
    }

    /**
     * Retrieves and clears any OTA Booking Not Entered form stashed by {@link #otaEntryNewGuest},
     * so the stash is consumed exactly once and can never leak into a later, unrelated OTA visit.
     *
     * @param session current HTTP session
     * @return the stashed OTA entry form, or a fresh empty form when nothing was stashed
     */
    private CreateRequest consumeStashedOtaEntryForm(HttpSession session) {
        Object stashed = session.getAttribute(OTA_ENTRY_WIZARD_SESSION_KEY);
        session.removeAttribute(OTA_ENTRY_WIZARD_SESSION_KEY);
        return stashed instanceof CreateRequest stashedForm ? stashedForm : emptyOtaEntryForm();
    }

    /**
     * Resolves the {@code createdGuestId} flash attribute set by {@code GuestPageController.create}
     * on a successful Guest creation redirect, confirming the identifier still resolves to a real
     * Guest before it is trusted for pre-selection.
     *
     * @param model model carrying any flash attributes merged in for this request
     * @return the created Guest identifier, or {@code null} when absent or no longer valid
     */
    private UUID resolveCreatedGuestId(Model model) {
        Object candidate = model.asMap().get("createdGuestId");
        if (!(candidate instanceof UUID createdGuestId)) {
            return null;
        }
        return guestQueryService.findForReservationCreation(createdGuestId) != null ? createdGuestId : null;
    }

    /**
     * Pre-selects the just-created Guest on a Walk-in form, when one is available, without
     * disturbing any other previously entered or restored Walk-in selection.
     *
     * @param walkInForm Walk-in form to pre-select the Guest on
     * @param model model carrying any flash attributes merged in for this request
     * @return the form with the created Guest selected, or the form unchanged when none applies
     */
    private WalkInRequest withCreatedGuestIfAny(WalkInRequest walkInForm, Model model) {
        UUID createdGuestId = resolveCreatedGuestId(model);
        return createdGuestId == null ? walkInForm : withGuestId(walkInForm, createdGuestId);
    }

    /**
     * Pre-selects the just-created Guest on an OTA Booking Not Entered form, when one is
     * available, without disturbing any other previously entered or restored OTA selection.
     *
     * @param otaEntryForm OTA entry form to pre-select the Guest on
     * @param model model carrying any flash attributes merged in for this request
     * @return the form with the created Guest selected, or the form unchanged when none applies
     */
    private CreateRequest withCreatedGuestIfAny(CreateRequest otaEntryForm, Model model) {
        UUID createdGuestId = resolveCreatedGuestId(model);
        return createdGuestId == null ? otaEntryForm : withGuestId(otaEntryForm, createdGuestId);
    }

    /**
     * Returns a copy of a Walk-in form with its Guest selection replaced, preserving every other
     * field.
     *
     * @param walkInForm form to copy
     * @param guestId Guest identifier to select
     * @return the form with the replaced Guest selection
     */
    private WalkInRequest withGuestId(WalkInRequest walkInForm, UUID guestId) {
        return new WalkInRequest(
                guestId,
                walkInForm.checkOutDate(),
                walkInForm.adultCount(),
                walkInForm.childCount(),
                walkInForm.currency(),
                walkInForm.notes(),
                walkInForm.rooms());
    }

    /**
     * Returns a copy of an OTA Booking Not Entered form with its Guest selection replaced,
     * preserving every other field.
     *
     * @param otaEntryForm form to copy
     * @param guestId Guest identifier to select
     * @return the form with the replaced Guest selection
     */
    private CreateRequest withGuestId(CreateRequest otaEntryForm, UUID guestId) {
        return new CreateRequest(
                guestId,
                otaEntryForm.checkInDate(),
                otaEntryForm.checkOutDate(),
                otaEntryForm.adultCount(),
                otaEntryForm.childCount(),
                otaEntryForm.source(),
                otaEntryForm.otaBookingReference(),
                otaEntryForm.currency(),
                otaEntryForm.notes(),
                otaEntryForm.rooms(),
                otaEntryForm.accompanyingGuestIdsOrEmpty(),
                otaEntryForm.bookingContactName(),
                otaEntryForm.bookingContactPhone(),
                otaEntryForm.bookingContactEmail());
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
