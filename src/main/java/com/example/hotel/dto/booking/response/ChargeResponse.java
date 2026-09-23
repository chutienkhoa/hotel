package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exposes a recorded Charge without server-controlled audit fields. */
public record ChargeResponse(
        UUID id,
        UUID stayId,
        String type,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        Instant chargedAt,
        String status,
        String voidReason) {}
