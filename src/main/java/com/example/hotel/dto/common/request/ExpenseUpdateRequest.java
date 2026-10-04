package com.example.hotel.dto.common.request;

import com.example.hotel.entity.common.ExpensePaymentMethod;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Contains the client-controlled business fields editable only while an Expense is a draft. */
public record ExpenseUpdateRequest(
        @NotNull UUID categoryId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6, message = "{validation.number.digits}") BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @NotNull ExpensePaymentMethod paymentMethod,
        String description) {}
