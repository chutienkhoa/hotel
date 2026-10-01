package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exposes Payment data without server-controlled audit fields. */
public record PaymentResponse(
        UUID id,
        UUID stayId,
        BigDecimal amount,
        String currency,
        BigDecimal exchangeRate,
        BigDecimal appliedAmount,
        String method,
        String status,
        Instant paidAt,
        String reference,
        String refundReason,
        String voidReason) {}
