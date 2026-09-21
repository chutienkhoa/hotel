package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.service.booking.PrepaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry points of the Prepayment operations; all require {@code MANAGE_PAYMENT}. */
@RestController
@RequestMapping("/api/reservations/{reservationId}/prepayments")
public class PrepaymentController {

    private final PrepaymentService service;

    /**
     * Creates the controller.
     *
     * @param service owner of the prepayment operations
     */
    PrepaymentController(PrepaymentService service) {
        this.service = service;
    }

    /**
     * Lists the Reservation's prepayments and totals.
     *
     * @param reservationId Reservation identifier
     * @return the summary
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PrepaymentSummaryResponse summary(@PathVariable UUID reservationId) {
        return service.summary(reservationId);
    }

    /**
     * Records a prepayment (immediately PAID).
     *
     * @param reservationId Reservation identifier
     * @param request payment fields
     * @return the recorded prepayment
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse record(@PathVariable UUID reservationId, @Valid @RequestBody PaymentCreateRequest request) {
        return service.record(reservationId, request);
    }

    /**
     * Refunds one prepayment in full.
     *
     * @param reservationId Reservation identifier
     * @param paymentId prepayment identifier
     * @param request refund reason
     * @return the refunded prepayment
     */
    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse refund(
            @PathVariable UUID reservationId, @PathVariable UUID paymentId, @RequestBody PaymentRefundRequest request) {
        return service.refund(reservationId, paymentId, request);
    }
}
