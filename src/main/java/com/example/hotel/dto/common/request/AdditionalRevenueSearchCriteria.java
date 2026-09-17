package com.example.hotel.dto.common.request;

import com.example.hotel.entity.common.AdditionalRevenueStatus;
import java.time.LocalDate;
import java.util.UUID;

/** Captures independent optional filters for the Additional Revenue list. */
public class AdditionalRevenueSearchCriteria {

    private LocalDate fromDate;
    private LocalDate toDate;
    private UUID categoryId;
    private AdditionalRevenueStatus status;

    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public AdditionalRevenueStatus getStatus() { return status; }
    public void setStatus(AdditionalRevenueStatus status) { this.status = status; }

    public boolean isAnyFilterActive() {
        return fromDate != null || toDate != null || categoryId != null || status != null;
    }

    public boolean isDateRangeInvalid() {
        return fromDate != null && toDate != null && fromDate.isAfter(toDate);
    }
}
