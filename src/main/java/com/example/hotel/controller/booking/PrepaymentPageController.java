package com.example.hotel.controller.booking;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Serves the narrow Prepayment operations of a CONFIRMED Reservation: record money received before check-in and refund
 * a prepayment in full. Owned by {@code MANAGE_PAYMENT}; every rule lives in {@link PrepaymentService}.
 */
@Controller
public class PrepaymentPageController {

    private final PrepaymentService prepaymentService;
    private final ReservationQueryService reservationQueryService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param prepaymentService owner of the prepayment operations
     * @param reservationQueryService reads the Reservation header
     * @param messageSource message source for localized feedback
     */
    public PrepaymentPageController(
            PrepaymentService prepaymentService,
            ReservationQueryService reservationQueryService,
            MessageSource messageSource) {
        this.prepaymentService = prepaymentService;
        this.reservationQueryService = reservationQueryService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the Record Prepayment form.
     *
     * @param id reservation identifier
     * @param model model used to render the form
     * @return the form template
     */
    @GetMapping("/reservations/{id}/prepayments")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String form(@PathVariable UUID id, Model model) {
        addFormAttributes(model, id, new PaymentCreateRequest(null, null, null, null, null));
        return "reservation/prepayment";
    }

    /**
     * Records the prepayment; a rejection redisplays the form with a localized message.
     *
     * @param id reservation identifier
     * @param form submitted payment fields
     * @param bindingResult structural validation
     * @param model model used to redisplay the form
     * @param redirectAttributes post-redirect feedback
     * @return the detail redirect on success, otherwise the form
     */
    @PostMapping("/reservations/{id}/prepayments")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String record(
            @PathVariable UUID id,
            @Valid @ModelAttribute("prepaymentForm") PaymentCreateRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            addFormAttributes(model, id, form);
            return "reservation/prepayment";
        }
        try {
            prepaymentService.record(id, form);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.prepayment.recorded"));
            return "redirect:/reservations/" + id;
        } catch (ResponseStatusException exception) {
            model.addAttribute("errorMessage", messages.error(exception));
            addFormAttributes(model, id, form);
            return "reservation/prepayment";
        }
    }

    /**
     * Refunds one prepayment in full and returns to the Reservation Detail.
     *
     * @param id reservation identifier
     * @param paymentId prepayment identifier
     * @param reason required refund reason
     * @param redirectAttributes post-redirect feedback
     * @return redirect to the detail page
     */
    @PostMapping("/reservations/{id}/prepayments/{paymentId}/refund")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    public String refund(
            @PathVariable UUID id,
            @PathVariable UUID paymentId,
            @RequestParam(required = false) String reason,
            RedirectAttributes redirectAttributes) {
        try {
            prepaymentService.refund(id, paymentId, new PaymentRefundRequest(reason));
            redirectAttributes.addFlashAttribute("successMessage", messages.get("payment.prepayment.refunded"));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.error(exception));
        }
        return "redirect:/reservations/" + id;
    }

    private void addFormAttributes(Model model, UUID id, PaymentCreateRequest form) {
        ReservationDetailResponse reservation = reservationQueryService.findById(id);
        model.addAttribute("reservation", reservation);
        model.addAttribute("prepaymentForm", form);
        model.addAttribute("paymentCurrencies", PaymentCurrency.values());
        model.addAttribute("paymentMethods", PaymentMethod.values());
    }
}
