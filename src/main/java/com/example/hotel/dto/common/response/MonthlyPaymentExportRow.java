package com.example.hotel.dto.common.response;

import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One Payment row of the monthly Excel export: cash collection anchored on {@code paidAt}, never revenue. A
 * REFUNDED Payment stays this same row; there is no refund event. Locale-free.
 *
 * @param paidAt real Instant the Payment was paid
 * @param reservationNumber Reservation number of the owning Stay
 * @param guestName Guest display name
 * @param method Payment method
 * @param reference optional Payment reference
 * @param amount original tender amount, never converted
 * @param currency tender currency
 * @param status PAID or REFUNDED
 */
public record MonthlyPaymentExportRow(
        Instant paidAt,
        String reservationNumber,
        String guestName,
        PaymentMethod method,
        String reference,
        BigDecimal amount,
        PaymentCurrency currency,
        PaymentStatus status) {}
