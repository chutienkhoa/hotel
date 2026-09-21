package com.example.hotel.repository.common;

import com.example.hotel.entity.common.ExpenseStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Narrow read projection of one Expense for the monthly Excel export, with category and creator resolved.
 *
 * @param expenseDate date the Expense is dated
 * @param categoryName ExpenseCategory name as stored
 * @param description optional description
 * @param amount amount in VND
 * @param status Expense status
 * @param createdByUsername username of the creating user, or {@code null} when unknown
 */
public record ExpenseExportRow(
        LocalDate expenseDate,
        String categoryName,
        String description,
        BigDecimal amount,
        ExpenseStatus status,
        String createdByUsername) {}
