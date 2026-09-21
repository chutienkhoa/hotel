package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * A Reservation's prepayments (money received before check-in) in the Reservation currency.
 *
 * @param currency Reservation currency
 * @param bookingTotal Reservation.totalAmount (original booking total)
 * @param activeTotal PAID prepayments not yet applied to a Stay
 * @param refundedTotal REFUNDED prepayments
 * @param receivedTotal active plus refunded
 * @param remaining booking total minus active prepayments
 * @param payments prepayment rows in creation order
 */
public record PrepaymentSummaryResponse(
        String currency,
        BigDecimal bookingTotal,
        BigDecimal activeTotal,
        BigDecimal refundedTotal,
        BigDecimal receivedTotal,
        BigDecimal remaining,
        List<PaymentResponse> payments) {}
