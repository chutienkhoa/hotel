package com.example.hotel.dto.common.request;

import com.example.hotel.entity.common.ExpenseStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Captures the independent, optional Expense list filters.
 */
public class ExpenseSearchCriteria {

    private LocalDate fromDate;
    private LocalDate toDate;
    private UUID categoryId;
    private ExpenseStatus status;

    /**
     * Returns the optional inclusive lower bound on expense date.
     *
     * @return the selected From Date, or {@code null} when absent
     */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /**
     * Sets the From Date filter supplied by the Expense list form.
     *
     * @param fromDate optional inclusive lower bound on expense date
     */
    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    /**
     * Returns the optional inclusive upper bound on expense date.
     *
     * @return the selected To Date, or {@code null} when absent
     */
    public LocalDate getToDate() {
        return toDate;
    }

    /**
     * Sets the To Date filter supplied by the Expense list form.
     *
     * @param toDate optional inclusive upper bound on expense date
     */
    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    /**
     * Returns the optional selected ExpenseCategory identifier filter.
     *
     * @return the selected category identifier, or {@code null} when absent
     */
    public UUID getCategoryId() {
        return categoryId;
    }

    /**
     * Sets the ExpenseCategory identifier filter supplied by the Expense list form.
     *
     * @param categoryId optional selected category identifier
     */
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * Returns the optional selected Expense status filter.
     *
     * @return the selected status, or {@code null} when absent
     */
    public ExpenseStatus getStatus() {
        return status;
    }

    /**
     * Sets the Expense status filter supplied by the Expense list form.
     *
     * @param status optional selected Expense status
     */
    public void setStatus(ExpenseStatus status) {
        this.status = status;
    }

    /**
     * Determines whether any filter field is currently populated.
     *
     * @return {@code true} when at least one filter is active
     */
    public boolean isAnyFilterActive() {
        return fromDate != null || toDate != null || categoryId != null || status != null;
    }

    /**
     * Determines whether the entered date range is invalid (From Date after To Date).
     *
     * @return {@code true} when both dates are present and From Date is after To Date
     */
    public boolean isDateRangeInvalid() {
        return fromDate != null && toDate != null && fromDate.isAfter(toDate);
    }
}
