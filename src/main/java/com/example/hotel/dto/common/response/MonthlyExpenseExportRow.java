package com.example.hotel.dto.common.response;

import com.example.hotel.entity.common.ExpenseStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One POSTED Expense row of the monthly Excel export. Locale-free.
 *
 * @param expenseDate date the Expense is dated
 * @param categoryName ExpenseCategory name as stored
 * @param description optional description
 * @param amount amount in VND
 * @param status Expense status (POSTED)
 * @param createdBy username of the creating user, or {@code null}
 */
public record MonthlyExpenseExportRow(
        LocalDate expenseDate,
        String categoryName,
        String description,
        BigDecimal amount,
        ExpenseStatus status,
        String createdBy) {}
