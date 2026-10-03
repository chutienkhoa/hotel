package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.ChargeVoidRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.response.ActivityTimelineItem;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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

/** Serves the reservation-scoped Folio page and delegates financial operations to services. */
@Controller
public class FolioPageController {

    /** Folio section shown when no {@code tab} query parameter is supplied or it is not recognized. */
    static final String TAB_OVERVIEW = "overview";

    /** Folio section listing every Charge and the Add Charge forms. */
    static final String TAB_CHARGES = "charges";

    /** Folio section listing every Payment with its detail panel; recording a Payment happens in a dialog. */
    static final String TAB_PAYMENTS = "payments";

    /** Number of most recent Charges, Payments and activity entries summarized on the Overview section. */
    private static final int RECENT_LIMIT = 5;

    private static final String STAY_CHECKED_IN = "CHECKED_IN";

    /** Add Charge input mode that prices a Charge by a fixed amount (the default). */
    private static final String CHARGE_MODE_FIXED = "FIXED";

    /** Add Charge input mode that prices a Charge by quantity and unit price. */
    private static final String CHARGE_MODE_ITEMIZED = "ITEMIZED";

    private final ReservationQueryService reservationQueryService;
    private final StayQueryService stayQueryService;
    private final com.example.hotel.common.i18n.UiMessages messages;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;
    private final ChargeService chargeService;
    private final PaymentService paymentService;
    private final StayBalanceService stayBalanceService;
    private final GuestQueryService guestQueryService;
    private final StayRoomAssignmentQueryService stayRoomAssignmentQueryService;
    private final StayExtensionService stayExtensionService;
    private final RoomQueryService roomQueryService;
    private final ReservationActivityQueryService reservationActivityQueryService;

    /**
     * Creates the Folio MVC controller with services that supply and mutate approved Folio data.
     *
     * @param reservationQueryService service used to load Reservation context
     * @param stayQueryService service used to resolve the Reservation's unique Stay
     * @param chargeService service used for approved Charge operations
     * @param paymentService service used for approved Payment operations
     * @param stayBalanceService service used to calculate authoritative Folio totals
     * @param guestQueryService service used to resolve the Reservation's Guest display name
     * @param stayRoomAssignmentQueryService service used to resolve the Stay's current room assignments
     * @param stayExtensionService service used to resolve the extension nights that make up the Stay's duration
     * @param roomQueryService service used to resolve the current rooms' Room Type
     * @param reservationActivityQueryService read-only Reservation Operational Timeline for Recent Activity
     * @param messageSource message source used for localized feedback
     * @param clockProvider hotel business clock used to flag a Stay that is past its planned check-out date
     */
    public FolioPageController(
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            ChargeService chargeService,
            PaymentService paymentService,
            StayBalanceService stayBalanceService,
            GuestQueryService guestQueryService,
            StayRoomAssignmentQueryService stayRoomAssignmentQueryService,
            StayExtensionService stayExtensionService,
            RoomQueryService roomQueryService,
            ReservationActivityQueryService reservationActivityQueryService,
            org.springframework.context.MessageSource messageSource,
            org.springframework.beans.factory.ObjectProvider<Clock> clockProvider) {
        this.clockProvider = clockProvider;
        this.messages = new com.example.hotel.common.i18n.UiMessages(messageSource);
        this.reservationQueryService = reservationQueryService;
        this.stayQueryService = stayQueryService;
        this.chargeService = chargeService;
        this.paymentService = paymentService;
        this.stayBalanceService = stayBalanceService;
        this.guestQueryService = guestQueryService;
        this.stayRoomAssignmentQueryService = stayRoomAssignmentQueryService;
        this.stayExtensionService = stayExtensionService;
        this.roomQueryService = roomQueryService;
        this.reservationActivityQueryService = reservationActivityQueryService;
    }

    /**
     * Displays the Folio for a Reservation with an existing Stay, one section at a time.
     *
     * @param reservationId Reservation identifier
     * @param tab requested section: {@code charges}, {@code payments}, or the Overview when absent
     * @param model model used to render the Folio
     * @param authentication current browser authentication
     * @return the Folio template name
     */
    @GetMapping("/reservations/{reservationId}/folio")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String folio(
            @PathVariable UUID reservationId,
            @RequestParam(required = false) String tab,
            Model model,
            Authentication authentication) {
        addFolioAttributes(
                model,
                reservationId,
                emptyChargeForm(),
                emptyPaymentForm(),
                resolveTab(tab),
                authentication);
        return "stay/folio";
    }

    /**
     * Records a Charge through the existing Charge service and returns to the Charges section on success.
     *
     * @param reservationId Reservation identifier used to resolve the owning Stay
     * @param chargeForm client-controlled Charge form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return Folio redirect on success or the Folio template on validation or business failure
     */
    @PostMapping("/reservations/{reservationId}/folio/charges")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String createCharge(
            @PathVariable UUID reservationId,
            @Valid @ModelAttribute("chargeForm") ChargeCreateRequest chargeForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFolioAttributes(model, reservationId, chargeForm, emptyPaymentForm(), TAB_CHARGES, authentication);
            model.addAttribute("addChargeOpen", true);
            model.addAttribute("addChargeMode", chargeMode(chargeForm));
            return "stay/folio";
        }
        try {
            chargeService.create(stayForReservation(reservationId).id(), chargeForm);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.folio.flash.chargeCreated"));
            return folioRedirect(reservationId, TAB_CHARGES);
        } catch (ResponseStatusException exception) {
            addFolioAttributes(model, reservationId, chargeForm, emptyPaymentForm(), TAB_CHARGES, authentication);
            model.addAttribute("addChargeOpen", true);
            model.addAttribute("addChargeMode", chargeMode(chargeForm));
            model.addAttribute("errorMessage", safeMessage(exception));
            return "stay/folio";
        }
    }

    /**
     * Voids an ACTIVE, non-ROOM Charge through the existing Charge service and returns to the Charges section.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param chargeId Charge identifier
     * @param reason staff-supplied void reason
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/charges/{chargeId}/void")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String voidCharge(
            @PathVariable UUID reservationId,
            @PathVariable UUID chargeId,
            @RequestParam(required = false) String reason,
            RedirectAttributes redirectAttributes) {
        try {
            chargeService.voidCharge(chargeId, new ChargeVoidRequest(reason));
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.folio.charge.void.success"));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return folioRedirect(reservationId, TAB_CHARGES);
    }

    /**
     * Records a pending Payment through the existing Payment service and returns to the Payments section.
     *
     * @param reservationId Reservation identifier used to resolve the owning Stay
     * @param paymentForm client-controlled Payment form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return Folio redirect on success or the Folio template on validation or business failure
     */
    @PostMapping("/reservations/{reservationId}/folio/payments")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String createPayment(
            @PathVariable UUID reservationId,
            @Valid @ModelAttribute("paymentForm") PaymentCreateRequest paymentForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, TAB_PAYMENTS, authentication);
            model.addAttribute("addPaymentOpen", true);
            return "stay/folio";
        }
        try {
            paymentService.create(stayForReservation(reservationId).id(), paymentForm);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.folio.flash.paymentPending"));
            return folioRedirect(reservationId, TAB_PAYMENTS);
        } catch (ResponseStatusException exception) {
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, TAB_PAYMENTS, authentication);
            model.addAttribute("addPaymentOpen", true);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "stay/folio";
        }
    }

    /**
     * Atomically records a Payment already received by Staff as PAID, through the existing
     * Payment service, without an intermediate PENDING Payment.
     *
     * @param reservationId Reservation identifier used to resolve the owning Stay
     * @param paymentForm client-controlled Payment form data
     * @param bindingResult structural validation result
     * @param model model used to redisplay invalid input
     * @param authentication current browser authentication
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return Folio redirect on success or the Folio template on validation or business failure
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/record-paid")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String recordPaidPayment(
            @PathVariable UUID reservationId,
            @Valid @ModelAttribute("paymentForm") PaymentCreateRequest paymentForm,
            BindingResult bindingResult,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, TAB_PAYMENTS, authentication);
            model.addAttribute("addPaymentOpen", true);
            return "stay/folio";
        }
        try {
            paymentService.recordPaid(stayForReservation(reservationId).id(), paymentForm);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.folio.flash.paymentPaid"));
            return folioRedirect(reservationId, TAB_PAYMENTS);
        } catch (ResponseStatusException exception) {
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, TAB_PAYMENTS, authentication);
            model.addAttribute("addPaymentOpen", true);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "stay/folio";
        }
    }

    /**
     * Marks a pending Payment as paid through the existing Payment service.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param paymentId Payment identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/{paymentId}/mark-paid")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String markPaymentPaid(
            @PathVariable UUID reservationId,
            @PathVariable UUID paymentId,
            RedirectAttributes redirectAttributes) {
        return redirectAfterPaymentAction(
                reservationId,
                redirectAttributes,
                messages.get("payment.folio.flash.paymentMarkedPaid"),
                () -> paymentService.markPaid(paymentId));
    }

    /**
     * Marks a pending Payment as failed through the existing Payment service.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param paymentId Payment identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/{paymentId}/mark-failed")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String markPaymentFailed(
            @PathVariable UUID reservationId,
            @PathVariable UUID paymentId,
            RedirectAttributes redirectAttributes) {
        return redirectAfterPaymentAction(
                reservationId,
                redirectAttributes,
                messages.get("payment.folio.flash.paymentMarkedFailed"),
                () -> paymentService.markFailed(paymentId));
    }

    /**
     * Refunds a paid Payment through the existing Payment service. V1 supports full refund only
     * and requires a non-blank refund reason.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param paymentId Payment identifier
     * @param reason staff-supplied refund reason
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/{paymentId}/refund")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String refundPayment(
            @PathVariable UUID reservationId,
            @PathVariable UUID paymentId,
            @RequestParam(required = false) String reason,
            RedirectAttributes redirectAttributes) {
        return redirectAfterPaymentAction(
                reservationId,
                redirectAttributes,
                messages.get("payment.folio.flash.paymentRefunded"),
                () -> paymentService.refund(paymentId, new PaymentRefundRequest(reason)));
    }

    /**
     * Voids a paid Payment recorded in error through the existing Payment service. Distinct from
     * {@link #refundPayment}: no money is claimed to have moved.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param paymentId Payment identifier
     * @param reason staff-supplied void reason
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/{paymentId}/void")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String voidPayment(
            @PathVariable UUID reservationId,
            @PathVariable UUID paymentId,
            @RequestParam(required = false) String reason,
            RedirectAttributes redirectAttributes) {
        return redirectAfterPaymentAction(
                reservationId,
                redirectAttributes,
                messages.get("payment.folio.payment.void.success"),
                () -> paymentService.voidPayment(paymentId, new PaymentVoidRequest(reason)));
    }

    /**
     * Adds all data required to render a Folio section, including the authoritative financial balance.
     *
     * @param model model used to render the Folio
     * @param reservationId Reservation identifier
     * @param chargeForm Charge form data to render
     * @param paymentForm Payment form data to render
     * @param activeTab section to render as active
     * @param authentication current browser authentication
     */
    private void addFolioAttributes(
            Model model,
            UUID reservationId,
            ChargeCreateRequest chargeForm,
            PaymentCreateRequest paymentForm,
            String activeTab,
            Authentication authentication) {
        ReservationDetailResponse reservation = reservationQueryService.findById(reservationId);
        StayResponse stay = stayForReservation(reservationId);
        StayBalance balance = stayBalanceService.calculate(stay.id());
        List<ChargeResponse> charges = chargeService.findByStayId(stay.id());
        List<PaymentResponse> payments = paymentService.findByStayId(stay.id());
        boolean folioMutable = STAY_CHECKED_IN.equals(stay.status());
        boolean canCheckOut = hasAuthority(authentication, "PERM_CHECK_OUT");

        model.addAttribute("activeTab", activeTab);
        model.addAttribute("reservation", reservation);
        model.addAttribute("stay", stay);
        model.addAttribute("guest", guestQueryService.findForReservationCreation(reservation.guestId()));
        List<CurrentRoomResponse> currentRooms = stayRoomAssignmentQueryService.findCurrentRooms(reservationId);
        model.addAttribute("currentRoomLabel", currentRoomLabel(currentRooms));
        model.addAttribute("currentRooms", currentRooms);
        model.addAttribute("canManageRoom", hasAuthority(authentication, "PERM_MANAGE_ROOM"));
        model.addAttribute("currentRoomTypeLabel", currentRoomTypeLabel(currentRooms));
        long nights = ChronoUnit.DAYS.between(reservation.checkInDate(), reservation.checkOutDate());
        long extensionNights = extensionNights(reservationId);
        model.addAttribute("nights", nights);
        model.addAttribute(
                "overdueDays",
                folioMutable && reservation.checkOutDate() != null
                        ? com.example.hotel.service.booking.OverdueDeparture.overdueDays(
                                reservation.checkOutDate(),
                                java.time.LocalDate.now(clockProvider.getIfAvailable(Clock::systemDefaultZone)))
                        : 0L);
        model.addAttribute("extensionNights", extensionNights);
        model.addAttribute("originalNights", nights - extensionNights);
        model.addAttribute("recentActivity", recentActivity(reservationId));
        model.addAttribute("balance", balance);
        model.addAttribute("outstandingSettled", balance.outstanding().compareTo(BigDecimal.ZERO) == 0);
        model.addAttribute("charges", charges);
        model.addAttribute("addChargeOpen", false);
        model.addAttribute("addPaymentOpen", false);
        model.addAttribute("addChargeMode", CHARGE_MODE_FIXED);
        model.addAttribute("payments", payments);
        model.addAttribute("recentCharges", recentChargesChronological(charges));
        model.addAttribute("recentPayments", latest(payments));
        model.addAttribute("chargeForm", chargeForm);
        model.addAttribute("paymentForm", paymentForm);
        model.addAttribute("chargeTypes", supportedChargeTypes());
        model.addAttribute("paymentMethods", List.of(PaymentMethod.values()));
        model.addAttribute("paymentCurrencies", List.of(PaymentCurrency.values()));
        model.addAttribute("folioMutable", folioMutable);
        model.addAttribute("canCheckOut", canCheckOut);
        model.addAttribute("checkoutReviewAvailable", folioMutable && canCheckOut);
        model.addAttribute("canManageGuest", hasAuthority(authentication, "PERM_MANAGE_GUEST"));
    }

    /**
     * Joins the Stay's current room numbers for the Folio identity line, using the open room assignments
     * (not the Reservation's booked rooms) so a Room Change is reflected immediately.
     *
     * @param currentRooms rooms currently occupied by the Stay
     * @return comma-separated room numbers, or {@code null} when the Stay has no current room
     */
    private static String currentRoomLabel(List<CurrentRoomResponse> currentRooms) {
        if (currentRooms.isEmpty()) {
            return null;
        }
        return currentRooms.stream()
                .map(CurrentRoomResponse::roomNumber)
                .collect(Collectors.joining(", "));
    }

    /**
     * Joins the Room Types of the Stay's current rooms, so the Stay Summary shows the room actually occupied
     * after a Room Change rather than the booked Room Type.
     *
     * @param currentRooms rooms currently occupied by the Stay
     * @return comma-separated distinct Room Type names, or {@code null} when the Stay has no current room
     */
    private String currentRoomTypeLabel(List<CurrentRoomResponse> currentRooms) {
        if (currentRooms.isEmpty()) {
            return null;
        }
        List<UUID> roomIds = currentRooms.stream().map(CurrentRoomResponse::roomId).toList();
        return roomQueryService.findAllByIds(roomIds).stream()
                .map(room -> room.roomType().name())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /**
     * Sums the nights added by every Stay extension, so the Nights card can show the original booked nights
     * next to the extension. Each extension event moves the planned check-out, so its added nights are the gap
     * between its previous and new planned check-out dates.
     *
     * @param reservationId Reservation identifier
     * @return total nights added by extensions, {@code 0} when the Stay was never extended
     */
    private long extensionNights(UUID reservationId) {
        return stayExtensionService.summary(reservationId).events().stream()
                .mapToLong(event -> ChronoUnit.DAYS.between(event.previousCheckOutDate(), event.newCheckOutDate()))
                .sum();
    }

    /**
     * Supplies the most recent Reservation Operational Timeline entries for Recent Activity, newest first, with
     * localized labels. Only audited actions are shown; no financial amount is read from the audit trail.
     *
     * @param reservationId Reservation identifier
     * @return up to {@link #RECENT_LIMIT} presentation rows, newest first
     */
    private List<ActivityTimelineItem> recentActivity(UUID reservationId) {
        List<ReservationActivityEntry> entries = reservationActivityQueryService.findByReservationId(reservationId);
        return latest(entries).stream()
                .map(entry -> new ActivityTimelineItem(
                        entry.occurredAt(), entry.actorDisplay(), activityLabel(entry.action())))
                .toList();
    }

    /**
     * Resolves one audited action to its localized Activity label, falling back to a generic localized label for
     * an action with no known mapping.
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
     * Chooses the Add Charge tab to reopen after a rejected submission: the mode the submitted values were entered in.
     * This is presentation only; the backend still validates the pricing mode.
     *
     * @param chargeForm submitted Charge form data
     * @return {@link #CHARGE_MODE_ITEMIZED} when quantity or unit price was submitted, otherwise {@link #CHARGE_MODE_FIXED}
     */
    private static String chargeMode(ChargeCreateRequest chargeForm) {
        return chargeForm.quantity() != null || chargeForm.unitPrice() != null
                ? CHARGE_MODE_ITEMIZED
                : CHARGE_MODE_FIXED;
    }

    /**
     * Returns the most recent entries of a chronologically ordered list, newest first.
     *
     * @param items entries in ascending chronological order, as returned by the Folio services
     * @param <T> entry type
     * @return up to {@link #RECENT_LIMIT} newest entries, newest first
     */
    private static <T> List<T> latest(List<T> items) {
        int fromIndex = Math.max(0, items.size() - RECENT_LIMIT);
        List<T> recent = new ArrayList<>(items.subList(fromIndex, items.size()));
        Collections.reverse(recent);
        return recent;
    }

    /**
     * Selects the most recent Charges for the Overview and orders them oldest first, newest last. Ordering uses the
     * actual {@code chargedAt} instant, with the Charge identifier as a deterministic tie-breaker for identical
     * timestamps. Only the presentation order changes; the Charges themselves are unchanged.
     *
     * @param charges Charges as returned by the Folio services
     * @return up to {@link #RECENT_LIMIT} most recent Charges in ascending chargedAt order
     */
    private static List<ChargeResponse> recentChargesChronological(List<ChargeResponse> charges) {
        List<ChargeResponse> sorted = charges.stream()
                .sorted(Comparator.comparing(ChargeResponse::chargedAt).thenComparing(ChargeResponse::id))
                .toList();
        return sorted.subList(Math.max(0, sorted.size() - RECENT_LIMIT), sorted.size());
    }

    /**
     * Normalizes a requested Folio section to one of the supported tabs.
     *
     * @param tab requested section, possibly {@code null}
     * @return {@link #TAB_CHARGES}, {@link #TAB_PAYMENTS}, or {@link #TAB_OVERVIEW}
     */
    private static String resolveTab(String tab) {
        if (TAB_CHARGES.equals(tab) || TAB_PAYMENTS.equals(tab)) {
            return tab;
        }
        return TAB_OVERVIEW;
    }

    /**
     * Resolves the presentation-safe Stay that belongs to the supplied Reservation.
     *
     * @param reservationId Reservation identifier
     * @return matching Stay response
     */
    private StayResponse stayForReservation(UUID reservationId) {
        return stayQueryService.findByReservationId(reservationId);
    }

    /**
     * Returns the Charge types staff may manually create from the Folio Add Charge form.
     *
     * <p>ROOM is excluded here even though it remains a Charge v1 type: it is created
     * automatically at check-in and rejected by {@code ChargeService} when submitted manually.</p>
     *
     * @return manually selectable Charge type list
     */
    private List<ChargeType> supportedChargeTypes() {
        return Arrays.stream(ChargeType.values())
                .filter(ChargeType::isSupportedInV1)
                .filter(type -> type != ChargeType.ROOM)
                .toList();
    }

    /**
     * Creates an empty Charge form model.
     *
     * @return empty Charge request data
     */
    private ChargeCreateRequest emptyChargeForm() {
        return new ChargeCreateRequest(null, null, null, null, null);
    }

    /**
     * Creates an empty Payment form model.
     *
     * @return empty Payment request data
     */
    private PaymentCreateRequest emptyPaymentForm() {
        return new PaymentCreateRequest(null, null, null, null, null);
    }

    /**
     * Redirects to the reservation-scoped Folio route, opening the section the action belongs to.
     *
     * @param reservationId Reservation identifier
     * @param tab section to reopen after the action
     * @return Folio redirect location
     */
    private String folioRedirect(UUID reservationId, String tab) {
        return "redirect:/reservations/" + reservationId + "/folio?tab=" + tab;
    }

    /**
     * Executes one Payment transition and supplies user-safe feedback after the operation.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @param successMessage feedback displayed after successful transition
     * @param action delegated existing Payment operation
     * @return redirect to the Payments section
     */
    private String redirectAfterPaymentAction(
            UUID reservationId,
            RedirectAttributes redirectAttributes,
            String successMessage,
            PaymentAction action) {
        try {
            action.execute();
            redirectAttributes.addFlashAttribute("successMessage", successMessage);
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", safeMessage(exception));
        }
        return folioRedirect(reservationId, TAB_PAYMENTS);
    }

    /**
     * Determines whether the current browser authentication includes one authority.
     *
     * @param authentication current browser authentication
     * @param authority required authority
     * @return {@code true} when the authority is granted
     */
    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    /**
     * Selects a safe browser message from an existing HTTP business exception.
     *
     * @param exception exception raised by a service operation
     * @return user-safe message
     */
    private String safeMessage(ResponseStatusException exception) {
        return messages.error(exception);
    }

    /** Represents an existing Payment operation invoked by a Folio action. */
    @FunctionalInterface
    private interface PaymentAction {

        /** Executes the delegated Payment operation. */
        void execute();
    }
}
