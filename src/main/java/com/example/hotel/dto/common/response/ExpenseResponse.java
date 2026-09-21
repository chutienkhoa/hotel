package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Exposes Expense v1 data without server-controlled audit fields. */
public record ExpenseResponse(
        UUID id,
        ExpenseCategoryResponse category,
        BigDecimal amount,
        String currency,
        LocalDate expenseDate,
        String paymentMethod,
        String description,
        String status,
        UUID approvedBy) {}
