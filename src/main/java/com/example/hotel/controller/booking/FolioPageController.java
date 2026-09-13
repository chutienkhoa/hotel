package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Arrays;
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

/** Serves the reservation-scoped Folio page and delegates financial operations to services. */
@Controller
public class FolioPageController {

    private final ReservationQueryService reservationQueryService;
    private final StayQueryService stayQueryService;
    private final ChargeService chargeService;
    private final PaymentService paymentService;
    private final StayBalanceService stayBalanceService;

    /**
     * Creates the Folio MVC controller with services that supply and mutate approved Folio data.
     *
     * @param reservationQueryService service used to load Reservation context
     * @param stayQueryService service used to resolve the Reservation's unique Stay
     * @param chargeService service used for approved Charge operations
     * @param paymentService service used for approved Payment operations
     * @param stayBalanceService service used to calculate authoritative Folio totals
     */
    public FolioPageController(
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            ChargeService chargeService,
            PaymentService paymentService,
            StayBalanceService stayBalanceService) {
        this.reservationQueryService = reservationQueryService;
        this.stayQueryService = stayQueryService;
        this.chargeService = chargeService;
        this.paymentService = paymentService;
        this.stayBalanceService = stayBalanceService;
    }

    /**
     * Displays the detailed Folio for a Reservation with an existing Stay.
     *
     * @param reservationId Reservation identifier
     * @param model model used to render the Folio
     * @param authentication current browser authentication
     * @return the Folio template name
     */
    @GetMapping("/reservations/{reservationId}/folio")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String folio(
            @PathVariable UUID reservationId, Model model, Authentication authentication) {
        addFolioAttributes(
                model,
                reservationId,
                emptyChargeForm(),
                emptyPaymentForm(),
                authentication);
        return "stay/folio";
    }

    /**
     * Records a Charge through the existing Charge service and returns to the Folio on success.
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
            addFolioAttributes(model, reservationId, chargeForm, emptyPaymentForm(), authentication);
            return "stay/folio";
        }
        try {
            chargeService.create(stayForReservation(reservationId).id(), chargeForm);
            redirectAttributes.addFlashAttribute("successMessage", "Charge recorded successfully.");
            return folioRedirect(reservationId);
        } catch (ResponseStatusException exception) {
            addFolioAttributes(model, reservationId, chargeForm, emptyPaymentForm(), authentication);
            model.addAttribute("errorMessage", safeMessage(exception));
            return "stay/folio";
        }
    }

    /**
     * Records a pending Payment through the existing Payment service and returns to the Folio.
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
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, authentication);
            return "stay/folio";
        }
        try {
            paymentService.create(stayForReservation(reservationId).id(), paymentForm);
            redirectAttributes.addFlashAttribute("successMessage", "Payment recorded as pending.");
            return folioRedirect(reservationId);
        } catch (ResponseStatusException exception) {
            addFolioAttributes(model, reservationId, emptyChargeForm(), paymentForm, authentication);
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
                "Payment marked as paid.",
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
                "Payment marked as failed.",
                () -> paymentService.markFailed(paymentId));
    }

    /**
     * Refunds a paid Payment through the existing Payment service.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param paymentId Payment identifier
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @return redirect to the Folio
     */
    @PostMapping("/reservations/{reservationId}/folio/payments/{paymentId}/refund")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String refundPayment(
            @PathVariable UUID reservationId,
            @PathVariable UUID paymentId,
            RedirectAttributes redirectAttributes) {
        return redirectAfterPaymentAction(
                reservationId,
                redirectAttributes,
                "Payment refunded successfully.",
                () -> paymentService.refund(paymentId));
    }

    /**
     * Adds all data required to render a Folio, including the authoritative financial balance.
     *
     * @param model model used to render the Folio
     * @param reservationId Reservation identifier
     * @param chargeForm Charge form data to render
     * @param paymentForm Payment form data to render
     * @param authentication current browser authentication
     */
    private void addFolioAttributes(
            Model model,
            UUID reservationId,
            ChargeCreateRequest chargeForm,
            PaymentCreateRequest paymentForm,
            Authentication authentication) {
        ReservationDetailResponse reservation = reservationQueryService.findById(reservationId);
        StayResponse stay = stayForReservation(reservationId);
        StayBalance balance = stayBalanceService.calculate(stay.id());
        boolean folioMutable = "CHECKED_IN".equals(stay.status());

        model.addAttribute("reservation", reservation);
        model.addAttribute("stay", stay);
        model.addAttribute("charges", chargeService.findByStayId(stay.id()));
        model.addAttribute("payments", paymentService.findByStayId(stay.id()));
        model.addAttribute("balance", balance);
        model.addAttribute("chargeForm", chargeForm);
        model.addAttribute("paymentForm", paymentForm);
        model.addAttribute("chargeTypes", supportedChargeTypes());
        model.addAttribute("paymentMethods", List.of(PaymentMethod.values()));
        model.addAttribute("folioMutable", folioMutable);
        model.addAttribute("canCheckOut", hasAuthority(authentication, "PERM_CHECK_OUT"));
        model.addAttribute(
                "checkoutReady",
                folioMutable
                        && "CHECKED_IN".equals(reservation.status())
                        && balance.outstanding().compareTo(BigDecimal.ZERO) == 0);
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
     * Returns the Charge types supported for creation in Folio v1.
     *
     * @return supported Charge type list
     */
    private List<ChargeType> supportedChargeTypes() {
        return Arrays.stream(ChargeType.values()).filter(ChargeType::isSupportedInV1).toList();
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
        return new PaymentCreateRequest(null, null, null);
    }

    /**
     * Redirects to the reservation-scoped Folio route.
     *
     * @param reservationId Reservation identifier
     * @return Folio redirect location
     */
    private String folioRedirect(UUID reservationId) {
        return "redirect:/reservations/" + reservationId + "/folio";
    }

    /**
     * Executes one Payment transition and supplies user-safe feedback after the operation.
     *
     * @param reservationId Reservation identifier used for the Folio redirect
     * @param redirectAttributes attributes used to display post-redirect feedback
     * @param successMessage feedback displayed after successful transition
     * @param action delegated existing Payment operation
     * @return redirect to the Folio
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
        return folioRedirect(reservationId);
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
        return exception.getReason() == null
                ? HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase()
                : exception.getReason();
    }

    /** Represents an existing Payment operation invoked by a Folio action. */
    @FunctionalInterface
    private interface PaymentAction {

        /** Executes the delegated Payment operation. */
        void execute();
    }
}
