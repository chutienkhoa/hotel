package com.example.hotel.controller.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.request.ReservationListCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.ActivityTimelineItem;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailEligibility;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationLifecycleResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryRoom;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationDetailEligibilityService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

    private static final String PREPAYMENT_BLOCKS_CANCEL = "payment.prepayment.error.blocksCancel";
    private static final String PREPAYMENT_BLOCKS_NO_SHOW = "payment.prepayment.error.blocksNoShow";

    /** How many of the newest Activity entries the compact Recent Activity card shows. */
    private static final int RECENT_ACTIVITY_LIMIT = 5;

    /** How many room codes the Reservation Detail summary strip lists before it shows "+N". */
    private static final int SUMMARY_ROOM_LIMIT = 2;

    private final ReservationQueryService reservationQueryService;
    private final ReservationService reservationService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;
    private final StayQueryService stayQueryService;
    private final StayBalanceService stayBalanceService;
    private final StayRoomAssignmentQueryService stayRoomAssignmentQueryService;
    private final StayExtensionService stayExtensionService;
    private final com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;
    private final com.example.hotel.service.booking.PrepaymentService prepaymentService;
    private final ReservationActivityQueryService reservationActivityQueryService;
    private final ReservationDetailEligibilityService detailEligibilityService;
    private final UiMessages messages;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;

    /**
     * Creates the MVC controller with query services for presentation data and the reservation
     * service for existing write operations.
     *
     * @param reservationQueryService service used to load reservation views
     * @param reservationService service used to execute existing reservation operations
     * @param guestQueryService service used to load guest choices
     * @param roomQueryService service used to load room choices
     * @param stayQueryService service used to resolve a Reservation's Stay
     * @param stayBalanceService the single authoritative Outstanding/Total Charges/Total Payments calculation
     * @param stayRoomAssignmentQueryService service used to supply current rooms and Room History
     * @param stayExtensionService service used to supply the extension history and derived accommodation totals
     * @param folioReconciliationService read-only financial integrity diagnostic (CHECKED_OUT standalone section)
     * @param prepaymentService prepayment summary of a CONFIRMED Reservation (MANAGE_PAYMENT only)
     * @param reservationActivityQueryService read-only Reservation Operational Timeline (VIEW_BOOKING baseline)
     * @param detailEligibilityService reports which date-dependent actions the backend would currently accept
     * @param messageSource localized UI message source
     * @param clockProvider hotel business clock used to flag a Stay that is past its planned check-out date
     */
    public ReservationPageController(
            ReservationQueryService reservationQueryService,
            ReservationService reservationService,
            GuestQueryService guestQueryService,
            RoomQueryService roomQueryService,
            StayQueryService stayQueryService,
            StayBalanceService stayBalanceService,
            StayRoomAssignmentQueryService stayRoomAssignmentQueryService,
            StayExtensionService stayExtensionService,
            com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService,
            com.example.hotel.service.booking.PrepaymentService prepaymentService,
            ReservationActivityQueryService reservationActivityQueryService,
            ReservationDetailEligibilityService detailEligibilityService,
            org.springframework.context.MessageSource messageSource,
            org.springframework.beans.factory.ObjectProvider<Clock> clockProvider) {
        this.clockProvider = clockProvider;
        this.reservationQueryService = reservationQueryService;
        this.reservationService = reservationService;
        this.guestQueryService = guestQueryService;
        this.roomQueryService = roomQueryService;
        this.stayQueryService = stayQueryService;
        this.stayBalanceService = stayBalanceService;
        this.stayRoomAssignmentQueryService = stayRoomAssignmentQueryService;
        this.stayExtensionService = stayExtensionService;
        this.folioReconciliationService = folioReconciliationService;
        this.prepaymentService = prepaymentService;
        this.reservationActivityQueryService = reservationActivityQueryService;
        this.detailEligibilityService = detailEligibilityService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the Reservation List (unified search, Stay date range, status and source filters, sortable columns,
     * database pagination) to users with reservation-view permission.
     *
     * @param searchCriteria submitted list filters and sort
     * @param bindingResult structural binding result for the submitted filters
     * @param page raw zero-based page request parameter
     * @param model model used to render the list view
     * @param authentication current browser authentication
     * @return the reservation list template name
     */
    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('PERM_VIEW_BOOKING')")
    public String list(
            @ModelAttribute("searchCriteria") ReservationListCriteria searchCriteria,
            BindingResult bindingResult,
            @RequestParam(required = false) String page,
            Model model,
            Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        searchCriteria.normalize();
        model.addAttribute("reservationStatuses", ReservationStatus.values());
        model.addAttribute("bookingSources", BookingSource.values());

        String sortKey = TableSorts.RESERVATION_LIST.key(searchCriteria.getSort(), searchCriteria.getDir());
        String sortDir =
                TableSorts.RESERVATION_LIST.activeDirection(searchCriteria.getSort(), searchCriteria.getDir());
        String validationMessage = validateListCriteria(searchCriteria, bindingResult);
        if (validationMessage != null) {
            model.addAttribute("errorMessage", validationMessage);
            Page<?> reservationPage = Page.empty();
            model.addAttribute("reservationPage", reservationPage);
            PaginationSupport.populate(model, reservationPage, "/reservations", filters(searchCriteria), sortKey, sortDir);
            return "reservation/list";
        }

        int requestedPage = PaginationSupport.parsePage(page);
        Page<?> reservationPage = reservationQueryService.findListPage(searchCriteria, requestedPage);
        String redirect = PaginationSupport.redirectWhenOutOfRange(
                reservationPage, requestedPage, "/reservations", filters(searchCriteria), sortKey, sortDir);
        if (redirect != null) {
            return redirect;
        }
        model.addAttribute("reservationPage", reservationPage);
        PaginationSupport.populate(model, reservationPage, "/reservations", filters(searchCriteria), sortKey, sortDir);
        return "reservation/list";
    }

    /**
     * Counts the calendar days a CHECKED_IN Reservation is past its planned check-out date. Presentation-only warning:
     * the business status stays CHECKED_IN.
     *
     * @param reservation the Reservation being displayed
     * @return days overdue, or {@code 0} when the Reservation is not CHECKED_IN or not past its planned check-out
     */
    private long overdueDays(ReservationDetailResponse reservation) {
        if (!"CHECKED_IN".equals(reservation.status()) || reservation.checkOutDate() == null) {
            return 0;
        }
        Clock clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
        return Math.max(0, ChronoUnit.DAYS.between(reservation.checkOutDate(), LocalDate.now(clock)));
    }

    /**
     * Displays one reservation through the shared state-aware Reservation Detail. The same shell serves every status;
     * the status only decides which data, tabs, cards and actions are present. Booking-snapshot rooms are used before
     * check-in, the current Stay assignments while checked in, and the Stay's room history after check-out, and the
     * three are never mixed.
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
        String status = reservation.status();
        boolean checkedIn = "CHECKED_IN".equals(status);
        boolean stayBearing = checkedIn || "CHECKED_OUT".equals(status);
        model.addAttribute("reservation", reservation);
        model.addAttribute("overdueDays", overdueDays(reservation));
        model.addAttribute("nights", ChronoUnit.DAYS.between(reservation.checkInDate(), reservation.checkOutDate()));
        model.addAttribute("notesMaxLength", NotesUpdateRequest.MAX_LENGTH);
        model.addAttribute("notesLength", reservation.notes() == null ? 0 : reservation.notes().length());
        model.addAttribute("hasStay", stayQueryService.existsByReservationId(id));
        StayResponse stay = stayBearing ? stayQueryService.findByReservationId(id) : null;
        model.addAttribute("stay", stay);
        model.addAttribute("guest", guestQueryService.findForReservationCreation(reservation.guestId()));
        model.addAttribute("stayExtensionSummary", stayBearing ? stayExtensionService.summary(id) : null);
        model.addAttribute("eligibility", eligibilityOf(reservation));
        addRoomAttributes(model, reservation, checkedIn, stayBearing);
        addFinancialAttributes(model, reservation, stay, authentication);
        List<ReservationActivityEntry> activityEntries = reservationActivityQueryService.findByReservationId(id);
        model.addAttribute("lifecycle", ReservationLifecycleResponse.from(activityEntries));
        List<ActivityTimelineItem> activity = resolveActivity(activityEntries);
        model.addAttribute("activity", activity);
        model.addAttribute("recentActivity", activity.subList(0, Math.min(RECENT_ACTIVITY_LIMIT, activity.size())));
        return "reservation/detail";
    }

    /**
     * Asks the backend which date-dependent actions it would accept now, so the page does not offer an action that is
     * guaranteed to be rejected. Only a CONFIRMED Reservation has such actions.
     *
     * @param reservation the Reservation being displayed
     * @return the eligibility, never {@code null}
     */
    private ReservationDetailEligibility eligibilityOf(ReservationDetailResponse reservation) {
        if (!"CONFIRMED".equals(reservation.status())) {
            return ReservationDetailEligibility.NONE;
        }
        ReservationDetailEligibility eligibility = detailEligibilityService.evaluate(
                reservation.id(), ReservationStatus.CONFIRMED, reservation.checkInDate());
        return eligibility == null ? ReservationDetailEligibility.NONE : eligibility;
    }

    /**
     * Resolves each raw Operational Timeline entry to a safe, localized presentation row, newest first. Never
     * exposes a raw AuditLog action string or financial detail; an action with no known mapping
     * falls back to a generic localized label instead of failing the page.
     *
     * @param entries raw Reservation Operational Timeline entries, oldest first
     * @return presentation-ready Activity Timeline items, newest first
     */
    private List<ActivityTimelineItem> resolveActivity(List<ReservationActivityEntry> entries) {
        List<ActivityTimelineItem> items = entries.stream()
                .map(entry -> new ActivityTimelineItem(
                        entry.occurredAt(), entry.actorDisplay(), activityLabel(entry.action()),
                        activityTone(entry.action()), entry.reservationStatus()))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        java.util.Collections.reverse(items);
        return List.copyOf(items);
    }

    /**
     * Resolves one audited action to its localized Activity label, falling back to a generic
     * localized label for an action with no known mapping (forward/backward compatibility).
     *
     * @param action stable AuditLog action identifier
     * @return the localized label to display
     */
    private String activityLabel(String action) {
        String key = "reservation.activity.action." + action;
        String resolved = messages.get(key);
        return resolved.equals(key) ? messages.get("reservation.activity.action.unknown") : resolved;
    }

    /**
     * Chooses the marker tone of one Activity entry from its stable action code only.
     *
     * @param action stable AuditLog action identifier
     * @return {@code success}, {@code danger}, {@code warning} or {@code neutral}
     */
    private String activityTone(String action) {
        return switch (action) {
            case "CONFIRM", "CHECK_IN", "CHECK_OUT" -> "success";
            case "CANCEL" -> "danger";
            case "NO_SHOW" -> "warning";
            default -> "neutral";
        };
    }

    /**
     * Adds the room presentation data for the Reservation's state. Before check-in (and after cancellation or
     * no-show) the booked {@code ReservationRoom} snapshot is the room data; while checked in it is the open
     * assignments with the lineage rate of each; after check-out it is the Stay's room history, with the final
     * rooms for the summary strip.
     *
     * @param model model used to render Reservation detail
     * @param reservation Reservation detail data
     * @param checkedIn whether the Reservation is CHECKED_IN
     * @param stayBearing whether the Reservation has a Stay (CHECKED_IN or CHECKED_OUT)
     */
    private void addRoomAttributes(
            Model model, ReservationDetailResponse reservation, boolean checkedIn, boolean stayBearing) {
        UUID id = reservation.id();
        boolean checkedOut = stayBearing && !checkedIn;
        List<CurrentRoomResponse> currentRooms = checkedIn ? stayRoomAssignmentQueryService.findCurrentRooms(id) : List.of();
        List<CurrentRoomResponse> finalRooms = checkedOut ? stayRoomAssignmentQueryService.findFinalRooms(id) : List.of();
        model.addAttribute("currentRooms", currentRooms);
        model.addAttribute("currentRoomRates", checkedIn ? stayRoomAssignmentQueryService.findCurrentRoomRates(id) : Map.of());
        model.addAttribute("roomHistory", stayBearing ? stayRoomAssignmentQueryService.findHistory(id) : List.of());
        List<ReservationSummaryRoom> summaryRooms;
        if (checkedIn) {
            summaryRooms = currentRooms.stream()
                    .map(room -> new ReservationSummaryRoom(room.roomId(), room.roomNumber(), room.roomTypeName()))
                    .toList();
        } else if (checkedOut) {
            summaryRooms = finalRooms.stream()
                    .map(room -> new ReservationSummaryRoom(room.roomId(), room.roomNumber(), room.roomTypeName()))
                    .toList();
        } else {
            summaryRooms = reservation.rooms().stream()
                    .map(room -> new ReservationSummaryRoom(room.roomId(), room.roomNumber(), room.roomTypeName()))
                    .toList();
        }
        // The strip stays compact for a large reservation: at most two room codes, then "+N".
        model.addAttribute("summaryRooms", summaryRooms.subList(0, Math.min(SUMMARY_ROOM_LIMIT, summaryRooms.size())));
        model.addAttribute("summaryRoomsMore", Math.max(0, summaryRooms.size() - SUMMARY_ROOM_LIMIT));
        model.addAttribute("summaryRoomCount", summaryRooms.size());
    }

    /**
     * Adds the financial summary data. Detailed folio data is MANAGE_PAYMENT-gated per spec sec. 8.2: CHECK_OUT alone
     * never grants Charge/Payment/detailed-financial read access. Reservation Detail only summarises; the Folio and
     * Prepayments pages hold the detail.
     *
     * @param model model used to render Reservation detail
     * @param reservation Reservation detail data
     * @param stay the Reservation's Stay, or {@code null} when it has none
     * @param authentication current browser authentication
     */
    private void addFinancialAttributes(
            Model model, ReservationDetailResponse reservation, StayResponse stay, Authentication authentication) {
        boolean canManagePayment = hasAuthority(authentication, "PERM_MANAGE_PAYMENT");
        boolean canSeeFinancial = stay != null && canManagePayment;
        StayBalance balance = canSeeFinancial ? stayBalanceService.calculate(stay.id()) : null;
        FolioReconciliationResponse integrity = canSeeFinancial ? folioReconciliationService.reconcile(stay.id()) : null;
        model.addAttribute("financialSummary", balance);
        model.addAttribute("chargeBreakdown", canSeeFinancial ? stayBalanceService.chargeBreakdown(stay.id()) : null);
        model.addAttribute("folioIndicator", folioIndicator(balance, integrity));
        model.addAttribute(
                "prepaymentSummary",
                "CONFIRMED".equals(reservation.status()) && canManagePayment
                        ? prepaymentService.summary(reservation.id()) : null);
    }

    /**
     * Chooses the staff-friendly folio indicator from the existing balance and reconciliation results. "Needs review"
     * appears only when the existing reconciliation actually reports a problem; "settled" only when the balance is zero
     * and nothing is flagged. No technical detail is exposed.
     *
     * @param balance the Stay balance, or {@code null} when it is not visible to the user
     * @param integrity the existing reconciliation result, or {@code null} when it is not available
     * @return {@code NEEDS_REVIEW}, {@code SETTLED}, or {@code null} when no indicator is meaningful
     */
    private String folioIndicator(StayBalance balance, FolioReconciliationResponse integrity) {
        if (integrity != null && !"MATCHED".equals(integrity.status())) {
            return "NEEDS_REVIEW";
        }
        if (balance != null && balance.outstanding().signum() == 0) {
            return "SETTLED";
        }
        return null;
    }

    /**
     * Displays the Create Reservation page. Guests and Rooms are not preloaded: the page searches Guests on demand and
     * lists Rooms for the selected stay dates through the existing lookup endpoints. A Guest just created through the
     * Create New Guest round trip arrives as the {@code createdGuestId} flash attribute and is pre-selected.
     *
     * @param model model used to render the creation form
     * @param authentication current browser authentication
     * @return the Create Reservation template name
     */
    @GetMapping("/reservations/new")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String createForm(Model model, Authentication authentication) {
        CreateRequest form = emptyReservationForm();
        UUID createdGuestId = resolveCreatedGuestId(model);
        if (createdGuestId != null) {
            form = withGuestId(form, createdGuestId);
            // The Create New Guest feedback is shown inside the Primary Guest card, so the generic flash message
            // set by Guest creation must not also be shown as a second notice.
            model.asMap().remove("successMessage");
        } else {
            // A flash value that names no existing Guest must not reach the page.
            model.asMap().remove("createdGuestId");
        }
        addCreateFormAttributes(model, form, authentication);
        return "reservation/create";
    }

    /** Displays the prepopulated edit form for a draft Reservation. */
    @GetMapping("/reservations/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String editForm(@PathVariable UUID id, Model model, Authentication authentication) {
        ReservationEditResponse reservation = reservationQueryService.findForEdit(id);
        if (!"DRAFT".equals(reservation.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only draft reservations can be edited");
        }
        CreateRequest form = new CreateRequest(
                reservation.guestId(),
                reservation.checkInDate(),
                reservation.checkOutDate(),
                reservation.adultCount(),
                reservation.childCount(),
                reservation.source(),
                reservation.otaBookingReference(),
                reservation.currency(),
                reservation.notes(),
                reservation.rooms().stream()
                        .map(room -> new RoomRequest(room.roomId(), room.nightlyRate()))
                        .toList(),
                reservation.accompanyingGuestIds(),
                reservation.bookingContactName(),
                reservation.bookingContactPhone(),
                reservation.bookingContactEmail());
        addReservationFormAttributes(
                model,
                form,
                authentication,
                null,
                reservation.guestId(),
                reservation.rooms().stream().map(room -> room.roomId()).toList());
        model.addAttribute("reservationId", id);
        return "reservation/form";
    }

    /**
     * Submits the existing reservation-create operation through a CSRF-protected session form. A rejected form is
     * redisplayed with its input preserved, inline errors on the offending fields and a summary for the global error
     * dialog; nothing is persisted.
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
        if (!bindingResult.hasErrors()) {
            try {
                Response response = reservationService.create(reservationForm);
                redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.create.success"));
                return "redirect:/reservations/" + response.id();
            } catch (ResponseStatusException exception) {
                rejectCreateFailure(bindingResult, reservationForm, exception);
            }
        }
        addCreateFormAttributes(model, reservationForm, authentication);
        model.addAttribute("feedbackTitle", messages.get("reservation.create.error.title"));
        model.addAttribute("errorMessage", messages.get("reservation.create.error.summaryIntro"));
        model.addAttribute(
                "validationSummary",
                ReservationCreateErrors.summary(
                        bindingResult,
                        messages::resolve,
                        (row, message) -> messages.get("reservation.create.error.roomRow", row, message)));
        return "reservation/create";
    }

    /** Submits a CSRF-protected replacement of editable draft Reservation data. */
    @PostMapping("/reservations/{id}/edit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String edit(
            @PathVariable UUID id,
            @Valid @ModelAttribute("reservationForm") CreateRequest reservationForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addReservationFormAttributes(model, reservationForm, authentication, null, reservationForm.guestId(), roomIds(reservationForm));
                model.addAttribute("reservationId", id);
            return "reservation/form";
        }
        try {
            reservationService.updateDraft(id, reservationForm);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("reservation.update.success"));
            return "redirect:/reservations/" + id;
        } catch (ResponseStatusException exception) {
            addReservationFormAttributes(model, reservationForm, authentication, null, reservationForm.guestId(), roomIds(reservationForm));
                model.addAttribute("reservationId", id);
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
        return redirectAfterAction(id, redirectAttributes, messages.get("reservation.confirm.success"),
                () -> reservationService.confirm(id));
    }

    /**
     * Cancels a confirmed reservation through the existing reservation service operation, requiring the
     * submitted structured cancellation reason.
     *
     * @param id reservation identifier
     * @param form submitted cancellation reason
     * @param bindingResult structural validation result
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String cancel(
            @PathVariable UUID id,
            @Valid @ModelAttribute("cancelForm") CancelReservationRequest form,
            BindingResult bindingResult,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstFieldError(bindingResult));
            return "redirect:/reservations/" + id;
        }
        return redirectAfterAction(id, redirectAttributes, messages.get("reservation.cancel.success"),
                () -> reservationService.cancel(id, form), authentication);
    }

    /**
     * Marks a confirmed reservation as no-show through the existing service operation, requiring the
     * submitted no-show reason.
     *
     * @param id reservation identifier
     * @param form submitted no-show reason
     * @param bindingResult structural validation result
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/no-show")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String noShow(
            @PathVariable UUID id,
            @Valid @ModelAttribute("noShowForm") NoShowReservationRequest form,
            BindingResult bindingResult,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstFieldError(bindingResult));
            return "redirect:/reservations/" + id;
        }
        return redirectAfterAction(id, redirectAttributes, messages.get("reservation.noShow.success"),
                () -> reservationService.noShow(id, form), authentication);
    }

    /** Returns the first field-level validation error message, already resolved in the request locale. */
    private String firstFieldError(BindingResult bindingResult) {
        var fieldError = bindingResult.getFieldErrors().stream().findFirst();
        return fieldError.map(org.springframework.validation.FieldError::getDefaultMessage)
                .orElse(bindingResult.getGlobalError() == null
                        ? "Invalid request"
                        : bindingResult.getGlobalError().getDefaultMessage());
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
     * Adds the data the Create Reservation page needs: the form values, the already-selected Primary and Accompanying
     * Guests, the selected Rooms' numbers, the current hotel date and the permission flags. Choices are never
     * preloaded; the page fetches them on demand.
     *
     * @param model model used to render the page
     * @param reservationForm form data to preserve, empty on first display
     * @param authentication current browser authentication
     */
    private void addCreateFormAttributes(Model model, CreateRequest reservationForm, Authentication authentication) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("reservationForm", reservationForm);
        model.addAttribute("bookingSources", BookingSource.values());
        model.addAttribute("selectedGuest", guestQueryService.findForReservationCreation(reservationForm.guestId()));
        model.addAttribute(
                "selectedAccompanyingGuests",
                guestQueryService.findAllByIds(reservationForm.accompanyingGuestIdsOrEmpty()));
        model.addAttribute("selectedRoomNumbers", selectedRoomNumbers(reservationForm));
        model.addAttribute("hotelToday", LocalDate.now(clockProvider.getIfAvailable(Clock::systemDefaultZone)));
        model.addAttribute("defaultAdultCount", Reservation.DEFAULT_ADULT_COUNT);
        model.addAttribute("defaultChildCount", Reservation.DEFAULT_CHILD_COUNT);
    }

    /**
     * Indexes the display number of each Room already chosen in a redisplayed form, so the page can name a Room whose
     * stay-date availability has not been looked up yet.
     *
     * @param reservationForm the form being redisplayed
     * @return Room numbers keyed by Room identifier, empty when no Room was chosen
     */
    private Map<UUID, String> selectedRoomNumbers(CreateRequest reservationForm) {
        List<UUID> roomIds = roomIds(reservationForm);
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        return roomQueryService.findAllByIds(roomIds).stream()
                .collect(Collectors.toMap(RoomResponse::id, RoomResponse::roomNumber, (first, second) -> first));
    }

    /**
     * Registers a service rejection of the create operation on the form: on the field the user can correct when the
     * rejection belongs to one, otherwise as a form-level error shown only in the error dialog.
     *
     * @param bindingResult the form's binding result
     * @param reservationForm the submitted form
     * @param exception the rejection raised by the create operation
     */
    private void rejectCreateFailure(
            BindingResult bindingResult, CreateRequest reservationForm, ResponseStatusException exception) {
        String message = safeMessage(exception);
        String field = ReservationCreateErrors.fieldFor(exception, reservationForm);
        if (field == null) {
            bindingResult.reject(ReservationCreateErrors.FAILURE_CODE, message);
        } else {
            bindingResult.rejectValue(field, ReservationCreateErrors.FAILURE_CODE, message);
        }
    }

    /**
     * Resolves the {@code createdGuestId} flash attribute set by {@code GuestPageController.create}, accepting it only
     * when it names a Guest that exists, so a stale or unknown value falls back to the ordinary empty form.
     *
     * @param model model carrying the redirect's flash attributes
     * @return the created Guest identifier, or {@code null} when none applies
     */
    private UUID resolveCreatedGuestId(Model model) {
        Object candidate = model.asMap().get("createdGuestId");
        if (!(candidate instanceof UUID createdGuestId)) {
            return null;
        }
        return guestQueryService.findForReservationCreation(createdGuestId) != null ? createdGuestId : null;
    }

    private CreateRequest withGuestId(CreateRequest form, UUID guestId) {
        return new CreateRequest(
                guestId,
                form.checkInDate(),
                form.checkOutDate(),
                form.adultCount(),
                form.childCount(),
                form.source(),
                form.otaBookingReference(),
                form.currency(),
                form.notes(),
                form.rooms(),
                form.accompanyingGuestIds(),
                form.bookingContactName(),
                form.bookingContactPhone(),
                form.bookingContactEmail());
    }

    /**
     * Adds lookup and authorization data required to render the draft Reservation edit form.
     *
     * @param model model used to render the form
     * @param reservationForm form data to preserve
     * @param authentication current browser authentication
     * @param selectedGuest unused by the edit form, kept {@code null}
     * @param currentGuestId Guest currently assigned to the draft
     * @param assignedRoomIds Rooms currently assigned to the draft, retained as choices
     */
    private void addReservationFormAttributes(
            Model model,
            CreateRequest reservationForm,
            Authentication authentication,
            GuestLookupResponse selectedGuest,
            UUID currentGuestId,
            List<UUID> assignedRoomIds) {
        addAuthorizationAttributes(model, authentication);
        model.addAttribute("reservationForm", reservationForm);
        model.addAttribute(
                "guests",
                currentGuestId == null
                        ? guestQueryService.findAllForReservationCreation()
                        : guestQueryService.findAllForReservationEditing(currentGuestId));
        model.addAttribute("rooms", assignedRoomIds.isEmpty()
                ? roomQueryService.findAllForReservationCreation()
                : roomQueryService.findAllForReservationEditing(assignedRoomIds));
        model.addAttribute("bookingSources", BookingSource.values());
        model.addAttribute("selectedGuest", selectedGuest);
        model.addAttribute(
                "selectedAccompanyingGuests",
                guestQueryService.findAllByIds(reservationForm.accompanyingGuestIdsOrEmpty()));
    }

    private List<UUID> roomIds(CreateRequest reservationForm) {
        if (reservationForm.rooms() == null) {
            return List.of();
        }
        return reservationForm.rooms().stream()
                .filter(java.util.Objects::nonNull)
                .map(RoomRequest::roomId)
                .filter(java.util.Objects::nonNull)
                .toList();
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
        model.addAttribute("canManageRoom", hasAuthority(authentication, "PERM_MANAGE_ROOM"));
        model.addAttribute("canCheckIn", hasAuthority(authentication, "PERM_CHECK_IN"));
        model.addAttribute("canCheckOut", hasAuthority(authentication, "PERM_CHECK_OUT"));
        model.addAttribute("canChangeRoom", hasAuthority(authentication, "PERM_CHANGE_ROOM"));
        model.addAttribute("canExtendStay", hasAuthority(authentication, "PERM_EXTEND_STAY"));
        model.addAttribute("canManagePayment", hasAuthority(authentication, "PERM_MANAGE_PAYMENT"));
        model.addAttribute("canViewReport", hasAuthority(authentication, "PERM_VIEW_REPORT"));
    }

    /**
     * Validates structural binding and the Stay date range of the Reservation List filters. A Stay date range needs
     * both dates, the end strictly after the start (the range is half-open), and at most one calendar year.
     *
     * @param criteria submitted list filters
     * @param bindingResult binding result for the submitted filters
     * @return a localized, user-safe validation message, or {@code null} when criteria are valid
     */
    private String validateListCriteria(ReservationListCriteria criteria, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return messages.get("reservation.list.error.invalidFilters");
        }
        LocalDate from = criteria.getStayFrom();
        LocalDate to = criteria.getStayTo();
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null) {
            return messages.get("reservation.list.error.stayIncomplete");
        }
        if (!to.isAfter(from)) {
            return messages.get("reservation.list.error.stayOrder");
        }
        if (to.isAfter(from.plusYears(1))) {
            return messages.get("reservation.list.error.stayTooLong");
        }
        return null;
    }

    /**
     * Collects the populated Reservation List filters for pagination and sort links.
     *
     * @param criteria normalized Reservation List filters
     * @return populated filters keyed by request parameter name
     */
    private Map<String, String> filters(ReservationListCriteria criteria) {
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, "search", criteria.getSearch());
        putIfPresent(filters, "stayFrom", criteria.getStayFrom() == null ? null : criteria.getStayFrom().toString());
        putIfPresent(filters, "stayTo", criteria.getStayTo() == null ? null : criteria.getStayTo().toString());
        putIfPresent(filters, "status", criteria.getStatus() == null ? null : criteria.getStatus().name());
        putIfPresent(filters, "source", criteria.getSource() == null ? null : criteria.getSource().name());
        return filters;
    }

    private void putIfPresent(Map<String, String> filters, String name, String value) {
        if (value != null) {
            filters.put(name, value);
        }
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
        return new CreateRequest(null, null, null, Reservation.DEFAULT_ADULT_COUNT, Reservation.DEFAULT_CHILD_COUNT,
                null, null, null, null, List.of(new RoomRequest(null, null)), List.of());
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
     * Redirects after a lifecycle operation that an active prepayment can block. When it is rejected for that reason
     * and the user may manage payments, the shared error dialog also offers {@code View Prepayments}.
     *
     * @param id reservation identifier
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @param successMessage message displayed when the operation succeeds
     * @param action existing service operation to execute
     * @param authentication current browser authentication
     * @return a redirect to the reservation detail page
     */
    private String redirectAfterAction(
            UUID id,
            RedirectAttributes redirectAttributes,
            String successMessage,
            ReservationAction action,
            Authentication authentication) {
        try {
            action.execute();
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
            if (isPrepaymentBlock(exception) && hasAuthority(authentication, "PERM_MANAGE_PAYMENT")) {
                redirectAttributes.addFlashAttribute("feedbackActionUrl", "/reservations/" + id + "/prepayments");
                redirectAttributes.addFlashAttribute(
                        "feedbackActionLabel", messages.get("reservation.error.viewPrepayments"));
            }
        }
        return "redirect:/reservations/" + id;
    }

    /**
     * Tells whether a rejection came from the active-prepayment guard of cancellation or no-show.
     *
     * @param exception exception raised by an existing service operation
     * @return {@code true} when an active prepayment blocked the operation
     */
    private boolean isPrepaymentBlock(ResponseStatusException exception) {
        return exception instanceof LocalizedResponseStatusException localized
                && (PREPAYMENT_BLOCKS_CANCEL.equals(localized.getMessageKey())
                        || PREPAYMENT_BLOCKS_NO_SHOW.equals(localized.getMessageKey()));
    }

    /**
     * Selects a user-safe message from a known HTTP business exception.
     *
     * @param exception exception raised by an existing service operation
     * @return a safe message for the browser
     */
    private String safeMessage(ResponseStatusException exception) {
        return messages.error(exception);
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
