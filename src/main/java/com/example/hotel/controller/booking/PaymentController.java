package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.service.booking.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the approved Payment v1 create, list, and explicit state-transition operations. */
@RestController
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Creates the Payment controller.
     *
     * @param paymentService service that owns Payment v1 behavior
     */
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Creates a pending Payment for a checked-in Stay.
     *
     * @param stayId owning Stay identifier
     * @param request validated client-controlled Payment fields
     * @return created pending Payment
     */
    @PostMapping("/api/stays/{stayId}/payments")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse create(@PathVariable UUID stayId, @Valid @RequestBody PaymentCreateRequest request) {
        return paymentService.create(stayId, request);
    }

    /**
     * Lists Payments recorded for one Stay.
     *
     * @param stayId owning Stay identifier
     * @return ordered Payment list
     */
    @GetMapping("/api/stays/{stayId}/payments")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    List<PaymentResponse> findByStayId(@PathVariable UUID stayId) {
        return paymentService.findByStayId(stayId);
    }

    /**
     * Marks one pending Payment as paid after overpayment validation.
     *
     * @param id Payment identifier
     * @return paid Payment
     */
    @PostMapping("/api/payments/{id}/mark-paid")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse markPaid(@PathVariable UUID id) {
        return paymentService.markPaid(id);
    }

    /**
     * Marks one pending Payment as failed.
     *
     * @param id Payment identifier
     * @return failed Payment
     */
    @PostMapping("/api/payments/{id}/mark-failed")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse markFailed(@PathVariable UUID id) {
        return paymentService.markFailed(id);
    }

    /**
     * Refunds one paid Payment using the same Payment record.
     *
     * @param id Payment identifier
     * @return refunded Payment
     */
    @PostMapping("/api/payments/{id}/refund")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    PaymentResponse refund(@PathVariable UUID id) {
        return paymentService.refund(id);
    }
}
