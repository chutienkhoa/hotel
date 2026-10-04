package com.example.hotel.controller.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.common.PaginationSupport;
import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.ReservationListCriteria;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.ActivityTimelineItem;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
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

    private final ReservationQueryService reservationQueryService;
    private final ReservationService reservationService;
    private final GuestQueryService guestQueryService;
    private final RoomQueryService roomQueryService;
    private final StayQueryService stayQueryService;
    private final StayBalanceService stayBalanceService;
    private final StayRoomAssignmentQueryService stayRoomAssignmentQueryService;
    private final StayExtensionService stayExtensionService;
    private final ChargeService chargeService;
    private final PaymentService paymentService;
    private final com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;
    private final com.example.hotel.service.booking.PrepaymentService prepaymentService;
    private final ReservationActivityQueryService reservationActivityQueryService;
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
     * @param chargeService read-only Charge listing for the Folio/Charges presentation (MANAGE_PAYMENT only)
     * @param paymentService read-only Payment listing for the Payments presentation (MANAGE_PAYMENT only)
     * @param folioReconciliationService read-only financial integrity diagnostic (CHECKED_OUT standalone section)
     * @param prepaymentService prepayment summary of a CONFIRMED Reservation (MANAGE_PAYMENT only)
     * @param reservationActivityQueryService read-only Reservation Operational Timeline (VIEW_BOOKING baseline)
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
            ChargeService chargeService,
            PaymentService paymentService,
            com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService,
            com.example.hotel.service.booking.PrepaymentService prepaymentService,
            ReservationActivityQueryService reservationActivityQueryService,
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
        this.chargeService = chargeService;
        this.paymentService = paymentService;
        this.folioReconciliationService = folioReconciliationService;
        this.prepaymentService = prepaymentService;
        this.reservationActivityQueryService = reservationActivityQueryService;
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
        model.addAttribute("overdueDays", overdueDays(reservation));
        boolean hasStay = stayQueryService.existsByReservationId(id);
        model.addAttribute("hasStay", hasStay);
        boolean checkedInLifecycle = "CHECKED_IN".equals(reservation.status()) || "CHECKED_OUT".equals(reservation.status());
        StayResponse stay = checkedInLifecycle ? stayQueryService.findByReservationId(id) : null;
        model.addAttribute("stay", stay);
        addRoomOccupancyAttributes(model, reservation);
        model.addAttribute("roomPricingByRoomId", roomPricingByRoomId(reservation));
        model.addAttribute("stayExtensionSummary", checkedInLifecycle ? stayExtensionService.summary(id) : null);
        // Detailed Folio financial data (Charges, Payments, totals) is MANAGE_PAYMENT-gated per spec
        // sec. 8.2: CHECK_OUT alone never grants Charge/Payment/detailed-financial read access.
        boolean canSeeFinancial = checkedInLifecycle && hasAuthority(authentication, "PERM_MANAGE_PAYMENT");
        // Financial Integrity (CHECKED_OUT-only presentation) remains an existing diagnostic, unaffected by the
        // Task33 Batch 3D CHECKED_IN mockup rebuild, which does not include this card.
        model.addAttribute("financialIntegrity", canSeeFinancial ? integrityFor(stay) : null);
        model.addAttribute(
                "financialSummary",
                canSeeFinancial && stay != null ? stayBalanceService.calculate(stay.id()) : null);
        model.addAttribute("charges", canSeeFinancial && stay != null ? activeCharges(stay.id()) : null);
        model.addAttribute("payments", canSeeFinancial && stay != null ? paidPayments(stay.id()) : null);
        model.addAttribute("prepaymentSummary",
                "CONFIRMED".equals(reservation.status())
                        && hasAuthority(authentication, "PERM_MANAGE_PAYMENT")
                        ? prepaymentService.summary(id) : null);
        List<ReservationActivityEntry> activityEntries = reservationActivityQueryService.findByReservationId(id);
        model.addAttribute("activity", resolveActivity(activityEntries));
        ReservationActivityEntry noteMeta = resolveNoteMeta(activityEntries);
        model.addAttribute("noteUpdatedAt", noteMeta == null ? null : noteMeta.occurredAt());
        model.addAttribute("noteUpdatedBy", noteMeta == null ? null : noteMeta.actorDisplay());
        // The Reservation Detail "hub" (CHECKED_IN) shows operationally useful Guest contact data
        // already surfaced elsewhere on this same page (the Booking Contact fallback); it is not a
        // new PERM_MANAGE_GUEST-gated exposure.
        model.addAttribute(
                "guest",
                "CHECKED_IN".equals(reservation.status())
                        ? guestQueryService.findForReservationCreation(reservation.guestId())
                        : null);
        return "reservation/detail";
    }

    /**
     * Resolves each raw Operational Timeline entry to a safe, localized presentation row. Never
     * exposes a raw AuditLog action string or financial detail; an action with no known mapping
     * falls back to a generic localized label instead of failing the page.
     *
     * @param entries raw Reservation Operational Timeline entries
     * @return presentation-ready Activity Timeline items, same order as supplied
     */
    private List<ActivityTimelineItem> resolveActivity(List<ReservationActivityEntry> entries) {
        return entries.stream()
                .map(entry -> new ActivityTimelineItem(
                        entry.occurredAt(), entry.actorDisplay(), activityLabel(entry.action())))
                .toList();
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
     * Resolves the Financial Integrity diagnostic for an already-resolved Stay.
     *
     * @param stay the Reservation's Stay, or {@code null} when none is available
     * @return the reconciliation result, or {@code null} when there is no Stay
     */
    private FolioReconciliationResponse integrityFor(StayResponse stay) {
        return stay == null ? null : folioReconciliationService.reconcile(stay.id());
    }

    /**
     * Resolves the most recent note-metadata entry (the Reservation's creation or a later notes update), used to
     * attribute the single real {@code Reservation.notes} value shown in Reservation Detail to a genuine audited
     * actor and timestamp instead of inventing one.
     *
     * @param entries raw Reservation Operational Timeline entries, oldest first
     * @return the latest matching entry, or {@code null} when none exists
     */
    private ReservationActivityEntry resolveNoteMeta(List<ReservationActivityEntry> entries) {
        ReservationActivityEntry latest = null;
        for (ReservationActivityEntry entry : entries) {
            if ("CREATE".equals(entry.action()) || "UPDATE_RESERVATION_NOTES".equals(entry.action())) {
                latest = entry;
            }
        }
        return latest;
    }

    /**
     * Lists the ACTIVE Charges for one Stay, the subset that contributes to the canonical Total Charges figure
     * ({@link StayBalanceService}), so the Folio/Charges presentation never shows a Charge that the total below it
     * does not already account for.
     *
     * @param stayId owning Stay identifier
     * @return the Stay's ACTIVE Charges in their existing chronological order
     */
    private List<ChargeResponse> activeCharges(UUID stayId) {
        return chargeService.findByStayId(stayId).stream()
                .filter(charge -> "ACTIVE".equals(charge.status()))
                .toList();
    }

    /**
     * Lists the PAID Payments for one Stay, the subset that contributes to the canonical Total Payments figure
     * ({@link StayBalanceService}), so the Payments presentation never shows a Payment that the total below it
     * does not already account for.
     *
     * @param stayId owning Stay identifier
     * @return the Stay's PAID Payments in their existing chronological order
     */
    private List<PaymentResponse> paidPayments(UUID stayId) {
        return paymentService.findByStayId(stayId).stream()
                .filter(payment -> "PAID".equals(payment.status()))
                .toList();
    }

    /**
     * Indexes each originally booked room's pricing snapshot by Room identifier, so Room Details can display Rate/
     * Nights/Total for a currently occupied room that still matches its original booking without re-deriving
     * pricing in the template.
     *
     * @param reservation Reservation detail data
     * @return the Reservation's booked-room pricing snapshots keyed by Room identifier
     */
    private Map<UUID, ReservationRoomResponse> roomPricingByRoomId(ReservationDetailResponse reservation) {
        return reservation.rooms().stream()
                .collect(Collectors.toMap(ReservationRoomResponse::roomId, room -> room, (first, second) -> first));
    }

    /**
     * Adds the current-room and Room History presentation data for a Reservation that has reached
     * Check-in. Before Check-in, the original ReservationRoom booking snapshot remains the correct
     * "assigned rooms" view and this data is intentionally left empty.
     *
     * @param model model used to render Reservation detail
     * @param reservation Reservation detail data
     */
    private void addRoomOccupancyAttributes(Model model, ReservationDetailResponse reservation) {
        boolean hasStay = "CHECKED_IN".equals(reservation.status()) || "CHECKED_OUT".equals(reservation.status());
        List<CurrentRoomResponse> currentRooms =
                hasStay ? stayRoomAssignmentQueryService.findCurrentRooms(reservation.id()) : List.of();
        model.addAttribute("currentRooms", currentRooms);
        model.addAttribute(
                "roomHistory",
                hasStay ? stayRoomAssignmentQueryService.findHistory(reservation.id()) : List.of());
        model.addAttribute("currentRoomDetails", roomDetailsByRoomId(currentRooms));
    }

    /**
     * Resolves Room Type and current operational status for each currently occupied room, so
     * Reservation Detail's Room Details presentation can show them without reimplementing Room
     * Management's own data.
     *
     * @param currentRooms the Stay's currently occupied rooms
     * @return Room profile data keyed by Room identifier
     */
    private Map<UUID, RoomResponse> roomDetailsByRoomId(List<CurrentRoomResponse> currentRooms) {
        if (currentRooms.isEmpty()) {
            return Map.of();
        }
        List<UUID> roomIds = currentRooms.stream().map(CurrentRoomResponse::roomId).toList();
        return roomQueryService.findAllByIds(roomIds).stream()
                .collect(java.util.stream.Collectors.toMap(RoomResponse::id, room -> room));
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
            redirectAttributes.addFlashAttribute("successMessage", "Reservation updated successfully.");
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
        return redirectAfterAction(id, redirectAttributes, "Reservation confirmed successfully.",
                () -> reservationService.confirm(id));
    }

    /**
     * Cancels a confirmed reservation through the existing reservation service operation, requiring the
     * submitted structured cancellation reason.
     *
     * @param id reservation identifier
     * @param form submitted cancellation reason
     * @param bindingResult structural validation result
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String cancel(
            @PathVariable UUID id,
            @Valid @ModelAttribute("cancelForm") CancelReservationRequest form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstFieldError(bindingResult));
            return "redirect:/reservations/" + id;
        }
        return redirectAfterAction(id, redirectAttributes, messages.get("reservation.cancel.success"),
                () -> reservationService.cancel(id, form));
    }

    /**
     * Marks a confirmed reservation as no-show through the existing service operation, requiring the
     * submitted no-show reason.
     *
     * @param id reservation identifier
     * @param form submitted no-show reason
     * @param bindingResult structural validation result
     * @param redirectAttributes attributes used to show post-redirect feedback
     * @return a redirect to the reservation detail page
     */
    @PostMapping("/reservations/{id}/no-show")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public String noShow(
            @PathVariable UUID id,
            @Valid @ModelAttribute("noShowForm") NoShowReservationRequest form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", firstFieldError(bindingResult));
            return "redirect:/reservations/" + id;
        }
        return redirectAfterAction(id, redirectAttributes, messages.get("reservation.noShow.success"),
                () -> reservationService.noShow(id, form));
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
