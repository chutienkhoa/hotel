package com.example.hotel.mapper.common;

import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.entity.common.Expense;
import com.example.hotel.entity.common.ExpenseCategory;
import org.springframework.stereotype.Component;

/** Converts Expense v1 entities and category reference data into client-safe responses. */
@Component
public class ExpenseMapper {

    /**
     * Maps an Expense entity to its client-safe representation.
     *
     * @param expense source Expense
     * @return Expense response without audit fields
     */
    public ExpenseResponse toResponse(Expense expense) {
        return new ExpenseResponse(
                expense.getId(),
                toCategoryResponse(expense.getCategory()),
                expense.getAmount(),
                expense.getCurrency(),
                expense.getExpenseDate(),
                expense.getPaymentMethod().name(),
                expense.getDescription(),
                expense.getStatus().name(),
                expense.getApprovedBy());
    }

    /**
     * Maps read-only Expense category reference data to a selection-safe response.
     *
     * @param category source Expense category
     * @return category response
     */
    public ExpenseCategoryResponse toCategoryResponse(ExpenseCategory category) {
        return new ExpenseCategoryResponse(category.getId(), category.getCode());
    }
}
