package com.example.hotel.controller.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.MissingRequiredField;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInRoomLine;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.OtaEntryReviewResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.dto.booking.response.WalkInRoomOption;
import com.example.hotel.exception.LocalizedResponseStatusException;
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
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
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

    /** Walk-in required fields, in the order the missing-fields dialog lists them. Check-in is fixed, Source/currency are DIRECT/VND. */
    private static final List<String> WALK_IN_REQUIRED_FIELDS =
            List.of("guestId", "checkOutDate", "adultCount", "childCount", "rooms");

    /** OTA required fields, in the order the missing-fields dialog lists them. Currency is fixed VND. */
    private static final List<String> OTA_REQUIRED_FIELDS = List.of(
            "guestId", "checkInDate", "checkOutDate", "adultCount", "childCount", "source", "otaBookingReference", "rooms");

    /** Number of rows shown in the landing's Recent Confirmed Reservations table. */
    private static final int RECENT_ARRIVALS_LIMIT = 5;

    private final com.example.hotel.service.booking.PrepaymentService prepaymentService;
    private final CheckInService checkInService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;
    private final FrontDeskQueryService frontDeskQueryService;
    private final com.example.hotel.service.room.RoomImageService roomImageService;
    private final jakarta.validation.Validator validator;
    private final UiMessages messages;

    /**
     * Creates the Check-in MVC controller with its collaborators.
     *
     * @param checkInService service implementing the Check-in operational flows
     * @param guestQueryService service used to load eligible Guest choices
     * @param roomQueryService service used to load Room choices for OTA Booking Not Entered
     * @param prepaymentService read-only prepayment summary shown on the Review
     * @param frontDeskQueryService read model for the landing's Recent Confirmed Reservations
     * @param roomImageService read model for the primary Room image shown on the Walk-in Summary
     * @param validator Bean Validation, used to re-check the prepared Walk-in/OTA form before the Summary is shown
     * @param messageSource resolves localized feedback text, such as the OTA creation success message
     */
    public CheckInPageController(
            CheckInService checkInService,
            GuestQueryService guestQueryService,
            RoomQueryService roomQueryService,
            com.example.hotel.service.booking.PrepaymentService prepaymentService,
            FrontDeskQueryService frontDeskQueryService,
            com.example.hotel.service.room.RoomImageService roomImageService,
            jakarta.validation.Validator validator,
            MessageSource messageSource) {
        this.validator = validator;
        this.messages = new UiMessages(messageSource);
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
     * @param sort optional public sort key for the Recent Confirmed Reservations table
     * @param dir optional sort direction, validated with the key
     * @param session HTTP session; returning to the landing abandons any in-progress Walk-in
     * @return the Check-in landing template name
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String landing(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            HttpSession session,
            Model model) {
        session.removeAttribute(WALK_IN_WIZARD_SESSION_KEY);
        session.removeAttribute(OTA_ENTRY_WIZARD_SESSION_KEY);
        model.addAttribute("recentArrivals", frontDeskQueryService.recentArrivals(RECENT_ARRIVALS_LIMIT, sort, dir));
        model.addAttribute("tableSortBase", "/check-in?");
        model.addAttribute("tableSortKey", TableSorts.FRONT_DESK_ARRIVALS.key(sort, dir));
        model.addAttribute("tableSortDir", TableSorts.FRONT_DESK_ARRIVALS.activeDirection(sort, dir));
        model.addAttribute("hotelToday", frontDeskQueryService.hotelToday());
        return "check-in/landing";
    }

    /**
     * Displays the Existing Reservation search: CONFIRMED Reservations without a Stay, optionally searched, filtered,
     * sorted and paginated through the Front Desk Arrivals read model (so readiness and overdue come from the one
     * canonical rule set). It is a plain GET resource; each row only links to the Check-in Review and never checks a
     * guest in.
     *
     * @param searchCriteria optional search, arrival date, readiness, source and sort state
     * @param page zero-based requested page number
     * @param model model used to render the search page
     * @return the Existing Reservation search template name, or a redirect to the last valid page
     */
    @GetMapping("/existing")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String existingSearch(
            @ModelAttribute("searchCriteria") FrontDeskSearchCriteria searchCriteria,
            @RequestParam(required = false) String page,
            Model model) {
        searchCriteria.normalize();
        int requestedPage = PaginationSupport.parsePage(page);
        Page<FrontDeskArrivalRow> reservationPage = frontDeskQueryService.confirmedReservations(searchCriteria, requestedPage);
        String sortKey = TableSorts.CHECK_IN_EXISTING.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir = TableSorts.CHECK_IN_EXISTING.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, "search", searchCriteria.getSearch());
        putIfPresent(filters, "arrivalDate", searchCriteria.getArrivalDate());
        putIfPresent(filters, "readiness", searchCriteria.getReadiness());
        putIfPresent(filters, "source", searchCriteria.getSource());
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                reservationPage, requestedPage, "/check-in/existing", filters, sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("reservationPage", reservationPage);
        model.addAttribute("bookingSources", BookingSource.values());
        model.addAttribute("clearSortQuery", sortKey == null ? "" : "?sort=" + sortKey + "&dir=" + sortDir);
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
        // Same read-only presentation data the Walk-in/OTA Summaries use: the Guest's stored identity details (ID / Passport
        // Number) and each Room's primary image, the latter offered only to users who may open Room Detail.
        addSummaryPresentation(model, review.guestId(), review.rooms(), authentication);
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
     * Displays the OTA Booking Not Entered form (step 1-3 of the OTA flow). The values shown come from, in order: a
     * form flashed by a redirected rejection (with its errors), the in-progress form kept in the session, or an empty
     * form. The session form is never consumed, so browser Back, Forward and Refresh keep it; returning from the
     * existing Guest creation form additionally pre-selects the created Guest.
     *
     * @param session HTTP session possibly holding the in-progress OTA form
     * @param model model used to render the form
     * @return the OTA entry template name
     */
    @GetMapping("/ota-entry")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntryForm(HttpSession session, Model model) {
        CreateRequest otaEntryForm = model.asMap().get("otaEntryForm") instanceof CreateRequest flashed
                ? flashed
                : inProgressOtaForm(session);
        addOtaEntryFormAttributes(model, withCreatedGuestIfAny(otaEntryForm, model, session));
        return "check-in/ota-entry";
    }

    /**
     * Keeps the OTA Booking Not Entered form's current, possibly incomplete, field values in the session for the
     * Guest-creation round trip, then redirects to the existing Guest creation form. No Reservation or Guest is
     * created by this step (spec 9.2.5).
     *
     * @param otaEntryForm current, possibly incomplete, OTA Booking Not Entered field values
     * @param session HTTP session used to carry the in-progress values across the Guest-creation round trip
     * @return a redirect to the existing Guest creation form with the OTA entry {@code returnTo} target
     */
    @PostMapping("/ota-entry/new-guest")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntryNewGuest(@ModelAttribute("otaEntryForm") CreateRequest otaEntryForm, HttpSession session) {
        session.setAttribute(OTA_ENTRY_WIZARD_SESSION_KEY, fixedVnd(otaEntryForm));
        return "redirect:/guests/new?returnTo=" + OTA_ENTRY_RETURN_TARGET;
    }

    /**
     * Returns the Rooms that can be booked for the whole OTA period {@code [checkInDate, checkOutDate)} with their
     * Room Type and adult capacity, shown by the OTA form's Room Selection table. Unlike the Walk-in list this is
     * date-aware and not limited to Rooms that are ready right now. It is UX guidance only; the review and the
     * final create-and-confirm independently re-validate.
     *
     * @param checkInDate requested check-in date
     * @param checkOutDate requested check-out date
     * @return period-available Room choices
     */
    @GetMapping("/ota-entry/available-rooms")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    @ResponseBody
    public List<WalkInRoomOption> otaAvailableRooms(
            @RequestParam("checkInDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam("checkOutDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        return checkInService.otaRoomOptions(checkInDate, checkOutDate);
    }

    /**
     * Validates the submitted OTA selections and prepares the Reservation Summary. Nothing is persisted. A valid form
     * is kept in the session and the browser is redirected to the Summary (POST/Redirect/GET); an invalid form is
     * redirected back to the OTA form with its errors, never rendered from this POST.
     *
     * @param otaEntryForm submitted OTA data
     * @param bindingResult structural validation result
     * @param session HTTP session that keeps the prepared form
     * @param redirectAttributes carries a rejected form and its errors across the redirect
     * @return a redirect to the Summary, or to the OTA form on a validation failure
     */
    @PostMapping("/ota-entry/review")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntryReview(
            @Valid @ModelAttribute("otaEntryForm") CreateRequest otaEntryForm,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return redirectToOtaForm(otaEntryForm, bindingResult, null, session, redirectAttributes);
        }
        try {
            checkInService.reviewOtaEntry(otaEntryForm);
        } catch (WalkInReviewException exception) {
            rejectOtaSelection(bindingResult, exception);
            return redirectToOtaForm(otaEntryForm, bindingResult, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectOtaFailure(otaEntryForm, bindingResult, exception, session, redirectAttributes);
        }
        session.setAttribute(OTA_ENTRY_WIZARD_SESSION_KEY, otaEntryForm);
        return "redirect:/check-in/ota-entry/review";
    }

    /**
     * Displays the read-only OTA Reservation Summary for the form prepared in the session. It is a plain GET
     * resource: refreshing it, or arriving at it by browser Back/Forward, repeats nothing and creates nothing, and it
     * does not clear the prepared form. When no valid prepared form exists, or it is no longer valid, the user is sent
     * back to the OTA form instead of seeing an error.
     *
     * @param session HTTP session holding the prepared form
     * @param model model used to render the Summary
     * @param authentication current browser authentication
     * @param redirectAttributes carries the notice or errors to the OTA form when the Summary cannot be shown
     * @return the Summary template, or a redirect to the OTA form
     */
    @GetMapping("/ota-entry/review")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntrySummary(
            HttpSession session, Model model, Authentication authentication, RedirectAttributes redirectAttributes) {
        if (!(session.getAttribute(OTA_ENTRY_WIZARD_SESSION_KEY) instanceof CreateRequest otaEntryForm)
                || !validator.validate(otaEntryForm).isEmpty()) {
            redirectAttributes.addFlashAttribute("otaNotice", "checkin.ota.summary.missing");
            return "redirect:/check-in/ota-entry";
        }
        BindingResult errors = new org.springframework.validation.DirectFieldBindingResult(otaEntryForm, "otaEntryForm");
        try {
            OtaEntryReviewResponse review = checkInService.reviewOtaEntry(otaEntryForm);
            model.addAttribute("review", review);
            model.addAttribute("otaEntryForm", otaEntryForm);
            addSummaryPresentation(model, review.guestId(), review.rooms(), authentication);
            return "check-in/ota-review";
        } catch (WalkInReviewException exception) {
            rejectOtaSelection(errors, exception);
            return redirectToOtaForm(otaEntryForm, errors, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectOtaFailure(otaEntryForm, errors, exception, session, redirectAttributes);
        }
    }

    /**
     * Creates and confirms an OTA Reservation (the Summary's Create Reservation action), then continues directly
     * into Check-in Review. It never checks the Guest in, so no Stay exists afterwards. The whole create-and-confirm
     * is one transaction ({@code CheckInService.createOtaEntry}): any failure leaves no DRAFT behind and the browser
     * is redirected back to the OTA form with the entered values and a localized message.
     *
     * @param otaEntryForm submitted OTA Reservation data, resubmitted from the Summary
     * @param bindingResult structural validation result
     * @param session HTTP session cleared of the in-progress OTA form on success
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect into Check-in Review on success, or to the OTA form on failure
     */
    @PostMapping("/ota-entry")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    public String otaEntrySubmit(
            @Valid @ModelAttribute("otaEntryForm") CreateRequest otaEntryForm,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return redirectToOtaForm(otaEntryForm, bindingResult, null, session, redirectAttributes);
        }
        try {
            // The same read-only rules as the Summary, so a selection that went stale after the Summary was shown is
            // reported next to its field; createOtaEntry then re-validates authoritatively under the room locks.
            checkInService.reviewOtaEntry(otaEntryForm);
            Response response = checkInService.createOtaEntry(otaEntryForm);
            session.removeAttribute(OTA_ENTRY_WIZARD_SESSION_KEY);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("checkin.ota.success"));
            return "redirect:/check-in/reservations/" + response.id();
        } catch (WalkInReviewException exception) {
            rejectOtaSelection(bindingResult, exception);
            return redirectToOtaForm(otaEntryForm, bindingResult, null, session, redirectAttributes);
        } catch (ResponseStatusException exception) {
            return redirectOtaFailure(otaEntryForm, bindingResult, exception, session, redirectAttributes);
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
        flashMissingRequiredFields(bindingResult, walkInForm.rooms(), WALK_IN_REQUIRED_FIELDS, Set.of(), redirectAttributes);
        return "redirect:/check-in/walk-in";
    }

    /**
     * Adds the OTA Booking Not Entered form's lookup data, restricted to OTA sources only. Rooms are not loaded here:
     * the form loads the Rooms available for the entered dates from the date-aware availability endpoint.
     *
     * @param model model used to render the form
     * @param otaEntryForm form data to preserve after a validation error
     */
    private void addOtaEntryFormAttributes(Model model, CreateRequest otaEntryForm) {
        model.addAttribute("otaEntryForm", otaEntryForm);
        model.addAttribute("guests", guestQueryService.findAllForReservationCreation());
        model.addAttribute("passportDocumentIds", checkInService.firstPassportDocumentIds());
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
        addSummaryPresentation(model, review.guestId(), review.rooms(), authentication);
    }

    /**
     * Adds the presentation data shared by the Walk-in and OTA Summaries: the selected Guest's stored details and
     * each selected Room's primary image (offered only to users holding {@code PERM_MANAGE_ROOM}).
     *
     * @param model model used to render the Summary
     * @param guestId selected Guest identifier
     * @param rooms selected room lines
     * @param authentication current browser authentication
     */
    private void addSummaryPresentation(
            Model model, UUID guestId, List<CheckInRoomLine> rooms, Authentication authentication) {
        model.addAttribute("summaryGuest", guestQueryService.findForReservationCreation(guestId));
        Map<UUID, UUID> primaryImageIds = new java.util.HashMap<>();
        if (hasAuthority(authentication, "PERM_MANAGE_ROOM")) {
            rooms.forEach(room -> roomImageService.findByRoomId(room.roomId()).stream()
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
        model.addAttribute("passportDocumentIds", checkInService.firstPassportDocumentIds());
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
                null, null, "VND", null, List.of(new RoomRequest(null, null)), List.of());
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
     * Returns the in-progress OTA form from the session WITHOUT removing it, so Back, Forward, Refresh and the
     * Create New Guest round trip keep it.
     *
     * @param session current HTTP session
     * @return the in-progress OTA form, or a fresh empty form when there is none
     */
    private CreateRequest inProgressOtaForm(HttpSession session) {
        return session.getAttribute(OTA_ENTRY_WIZARD_SESSION_KEY) instanceof CreateRequest form ? form : emptyOtaEntryForm();
    }

    /** Returns the form with the Reservation currency fixed to VND, the only V1 Reservation currency. */
    private CreateRequest fixedVnd(CreateRequest form) {
        return new CreateRequest(form.guestId(), form.checkInDate(), form.checkOutDate(), form.adultCount(),
                form.childCount(), form.source(), form.otaBookingReference(), "VND", form.notes(), form.rooms(),
                form.accompanyingGuestIdsOrEmpty(), form.bookingContactName(), form.bookingContactPhone(),
                form.bookingContactEmail());
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
     * @param session HTTP session that keeps the in-progress OTA form
     * @return the form with the created Guest selected, or the form unchanged when none applies
     */
    private CreateRequest withCreatedGuestIfAny(CreateRequest otaEntryForm, Model model, HttpSession session) {
        UUID createdGuestId = resolveCreatedGuestId(model);
        if (createdGuestId == null) {
            return otaEntryForm;
        }
        // Kept in the session too, so Refresh or Back after the one-shot createdGuestId flash is gone still shows it.
        CreateRequest withGuest = withGuestId(otaEntryForm, createdGuestId);
        session.setAttribute(OTA_ENTRY_WIZARD_SESSION_KEY, withGuest);
        return withGuest;
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

    /**
     * Redirects a rejected OTA form back to the OTA page (POST/Redirect/GET) so that no history entry is a rendered
     * POST. The form is kept in the session and flashed with its errors, so the page shows the entered values and the
     * messages next to their fields.
     *
     * @param otaEntryForm the rejected form
     * @param bindingResult its validation errors
     * @param errorMessage optional general error text
     * @param session HTTP session that keeps the in-progress form
     * @param redirectAttributes flash carrier
     * @return the redirect to the OTA form
     */
    private String redirectToOtaForm(
            CreateRequest otaEntryForm,
            BindingResult bindingResult,
            String errorMessage,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        session.setAttribute(OTA_ENTRY_WIZARD_SESSION_KEY, otaEntryForm);
        redirectAttributes.addFlashAttribute("otaEntryForm", otaEntryForm);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "otaEntryForm", bindingResult);
        if (errorMessage != null) {
            redirectAttributes.addFlashAttribute("errorMessage", errorMessage);
        }
        Set<String> blankReference = otaEntryForm.otaBookingReference() == null || otaEntryForm.otaBookingReference().isBlank()
                ? Set.of("otaBookingReference")
                : Set.of();
        flashMissingRequiredFields(bindingResult, otaEntryForm.rooms(), OTA_REQUIRED_FIELDS, blankReference, redirectAttributes);
        return "redirect:/check-in/ota-entry";
    }

    /**
     * Flashes the required fields the rejected Walk-in/OTA form left empty so the page can raise one feedback dialog
     * that lists every one of them (and marks them invalid). Only a field whose rejected value is empty counts: a
     * value that was entered but refused (capacity, date range, duplicate reference, a non-positive rate) is a
     * domain/format error and stays on the existing inline mechanism. {@code requiredFieldErrorsOnly} tells the page
     * the generic "correct the highlighted fields" banner has nothing left to say.
     *
     * @param bindingResult validation result of the rejected form
     * @param rooms the rejected form's room rows, used to name the Room No. of a missing rate
     * @param fieldOrder the form's required fields, in the order they are listed
     * @param blankButUnvalidated required fields that are empty but raised no field error of their own, because their
     *     rule depends on another field (the OTA reference is only validated once a source is chosen); they are
     *     listed so the operator sees every empty required field at once
     * @param redirectAttributes flash carrier
     */
    private void flashMissingRequiredFields(
            BindingResult bindingResult,
            List<RoomRequest> rooms,
            List<String> fieldOrder,
            Set<String> blankButUnvalidated,
            RedirectAttributes redirectAttributes) {
        List<MissingRequiredField> missing = new ArrayList<>();
        for (String path : fieldOrder) {
            if (isMissing(bindingResult.getFieldError(path)) || blankButUnvalidated.contains(path)) {
                missing.add(new MissingRequiredField(path, path, null));
            }
        }
        Map<UUID, String> roomNumbers = new HashMap<>();
        roomQueryService.findAllByIds(
                        rooms == null ? List.of() : rooms.stream().map(RoomRequest::roomId).filter(Objects::nonNull).toList())
                .forEach(room -> roomNumbers.put(room.id(), room.roomNumber()));
        List<MissingRequiredField> rates = new ArrayList<>();
        boolean roomMissing = missing.stream().anyMatch(field -> "rooms".equals(field.path()));
        for (int index = 0; rooms != null && index < rooms.size(); index++) {
            if (isMissing(bindingResult.getFieldError("rooms[" + index + "].roomId")) && !roomMissing) {
                missing.add(new MissingRequiredField("rooms[" + index + "].roomId", "rooms", null));
                roomMissing = true;
            }
            if (isMissing(bindingResult.getFieldError("rooms[" + index + "].nightlyRate"))) {
                rates.add(new MissingRequiredField(
                        "rooms[" + index + "].nightlyRate", "nightlyRate", roomNumbers.get(rooms.get(index).roomId())));
            }
        }
        missing.addAll(rates);
        boolean onlyMissing = !missing.isEmpty()
                && bindingResult.getAllErrors().stream()
                        .allMatch(error -> error instanceof FieldError fieldError && isMissing(fieldError));
        redirectAttributes.addFlashAttribute("missingFields", missing);
        redirectAttributes.addFlashAttribute("requiredFieldErrorsOnly", onlyMissing);
    }

    private static boolean isMissing(FieldError error) {
        if (error == null) {
            return false;
        }
        Object value = error.getRejectedValue();
        return value == null
                || (value instanceof CharSequence text && text.toString().isBlank())
                || (value instanceof Collection<?> collection && collection.isEmpty());
    }

    /**
     * Reports a service rejection on the OTA form: a duplicate OTA identity is shown on the reference field, any
     * other rejection as a general localized message.
     *
     * @param otaEntryForm the rejected form
     * @param bindingResult the form's binding result
     * @param exception the rejection raised by the review or create operation
     * @param session HTTP session that keeps the in-progress form
     * @param redirectAttributes flash carrier
     * @return the redirect to the OTA form
     */
    private String redirectOtaFailure(
            CreateRequest otaEntryForm,
            BindingResult bindingResult,
            ResponseStatusException exception,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        if (exception instanceof LocalizedResponseStatusException localized
                && "reservation.ota.error.duplicateIdentity".equals(localized.getMessageKey())) {
            bindingResult.rejectValue(
                    "otaBookingReference", localized.getMessageKey(), localized.getMessageArguments(),
                    exception.getReason());
            return redirectToOtaForm(otaEntryForm, bindingResult, null, session, redirectAttributes);
        }
        return redirectToOtaForm(otaEntryForm, bindingResult, messages.error(exception), session, redirectAttributes);
    }

    /**
     * Reports a rejected OTA selection on the field it concerns, with a localized message resolved from
     * {@code checkin.ota.error.<REASON>}.
     *
     * @param bindingResult binding result of the redisplayed OTA form
     * @param exception the rejection raised by the OTA review
     */
    private void rejectOtaSelection(BindingResult bindingResult, WalkInReviewException exception) {
        String field =
                switch (exception.getReason()) {
                    case CHECK_OUT_NOT_AFTER_CHECK_IN -> "checkOutDate";
                    case INSUFFICIENT_ADULT_CAPACITY, CAPACITY_NOT_CONFIGURED -> "adultCount";
                    case DUPLICATE_ROOM, ROOM_UNAVAILABLE -> "rooms";
                };
        bindingResult.rejectValue(
                field, "checkin.ota.error." + exception.getReason().name(), exception.getArguments(),
                exception.getMessage());
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
