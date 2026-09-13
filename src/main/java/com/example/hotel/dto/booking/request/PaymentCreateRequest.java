package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Contains the only client-controlled fields accepted to create a pending Payment. */
public record PaymentCreateRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull PaymentMethod method,
        @Size(max = 1000) String reference) {}
