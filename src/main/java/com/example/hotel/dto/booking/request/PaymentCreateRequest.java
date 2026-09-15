package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Contains the only client-controlled fields accepted to create a pending Payment.
 *
 * <p>{@code exchangeRate} is intentionally not {@code @NotNull}: it is required only for a
 * cross-currency Payment and must be {@code null} for a same-currency Payment — that conditional
 * rule is enforced by {@code PaymentService}, the single authoritative calculation path, not by
 * Bean Validation. This DTO never accepts an {@code appliedAmount} — the backend always computes
 * it.</p>
 */
public record PaymentCreateRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull PaymentCurrency currency,
        @Positive BigDecimal exchangeRate,
        @NotNull PaymentMethod method,
        @Size(max = 1000) String reference) {}
