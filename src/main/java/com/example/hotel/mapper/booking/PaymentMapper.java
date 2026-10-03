package com.example.hotel.mapper.booking;

import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import org.springframework.stereotype.Component;

/** Converts Payment entities into client-safe API responses. */
@Component
public class PaymentMapper {

    /**
     * Maps one Payment entity to its response representation.
     *
     * @param payment source Payment
     * @return client-safe Payment response
     */
    public PaymentResponse toResponse(Payment payment) {
        return toResponse(payment, null);
    }

    /**
     * Maps one Payment entity to its response representation, including the "added by" username already resolved
     * by the caller (batch-resolved to avoid an N+1 lookup per row).
     *
     * @param payment source Payment
     * @param addedByUsername the username of the staff member who recorded the Payment, or {@code null}
     * @return client-safe Payment response
     */
    public PaymentResponse toResponse(Payment payment, String addedByUsername) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStay() == null ? null : payment.getStay().getId(),
                payment.getAmount(),
                payment.getCurrency().name(),
                payment.getExchangeRate(),
                payment.getAppliedAmount(),
                payment.getMethod().name(),
                payment.getStatus().name(),
                payment.getPaidAt(),
                payment.getReference(),
                payment.getRefundReason(),
                payment.getVoidReason(),
                addedByUsername);
    }
}
