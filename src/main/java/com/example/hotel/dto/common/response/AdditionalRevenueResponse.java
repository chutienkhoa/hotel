package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Exposes Additional Revenue data without mutable audit metadata. */
public record AdditionalRevenueResponse(
        UUID id,
        AdditionalRevenueCategoryResponse category,
        BigDecimal amount,
        String currency,
        LocalDate revenueDate,
        String paymentMethod,
        String description,
        String status,
        String voidReason,
        Instant voidedAt,
        UUID voidedBy) {}
