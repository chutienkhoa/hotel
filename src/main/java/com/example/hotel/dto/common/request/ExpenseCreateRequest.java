package com.example.hotel.dto.common.request;

import com.example.hotel.entity.common.ExpensePaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Contains the only client-controlled fields accepted to create an Expense v1 draft. */
public record ExpenseCreateRequest(
        @NotNull UUID categoryId,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @NotNull ExpensePaymentMethod paymentMethod,
        String description) {}
