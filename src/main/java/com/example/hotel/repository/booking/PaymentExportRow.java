package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Narrow read projection of one Payment for the monthly Excel export, reached through Stay, Reservation and Guest.
 *
 * @param paidAt real Instant the Payment was recorded as paid
 * @param reservationNumber Reservation number of the owning Stay
 * @param guestFirstName Guest first name
 * @param guestLastName Guest last name
 * @param method Payment method
 * @param reference optional Payment reference
 * @param amount original tender amount (never converted)
 * @param currency tender currency
 * @param status Payment status
 */
public record PaymentExportRow(
        Instant paidAt,
        String reservationNumber,
        String guestFirstName,
        String guestLastName,
        PaymentMethod method,
        String reference,
        BigDecimal amount,
        PaymentCurrency currency,
        PaymentStatus status) {}
