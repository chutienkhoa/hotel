package com.example.hotel.dto.common.request;

import com.example.hotel.entity.common.AdditionalRevenuePaymentMethod;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Contains client-controlled values editable only while Additional Revenue is recorded. */
public record AdditionalRevenueUpdateRequest(
        @NotNull UUID categoryId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6, message = "{validation.number.digits}") BigDecimal amount,
        @NotNull LocalDate revenueDate,
        @NotNull AdditionalRevenuePaymentMethod paymentMethod,
        String description) {}
