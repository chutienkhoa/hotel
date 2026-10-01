package com.example.hotel.dto.common.request;

/** Captures the optional Staff Management list filters: free-text search and status. */
public class StaffSearchCriteria {

    private String query;
    private String status;

    /**
     * Returns the optional case-insensitive free-text search fragment, matched against Staff
     * Code, first name, last name, phone, email, and position.
     *
     * @return the supplied search fragment, or {@code null} when absent
     */
    public String getQuery() {
        return query;
    }

    /**
     * Sets the free-text search fragment supplied by the Staff list form.
     *
     * @param query optional free-text search fragment
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * Returns the selected status filter: {@code ALL}, {@code ACTIVE}, or {@code INACTIVE}.
     *
     * @return the selected status filter, or {@code null}/{@code ALL} when unfiltered
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the status filter supplied by the Staff list form.
     *
     * @param status selected status filter
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Trims the free-text query and converts a blank value to an absent filter.
     */
    public void normalize() {
        if (query != null) {
            String trimmed = query.trim();
            query = trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * Determines whether the status filter should be applied.
     *
     * @return {@code true} when the status filter is {@code ACTIVE} or {@code INACTIVE}
     */
    public boolean hasStatusFilter() {
        return "ACTIVE".equalsIgnoreCase(status) || "INACTIVE".equalsIgnoreCase(status);
    }

    /**
     * Determines whether the status filter requires active Staff.
     *
     * @return {@code true} when the status filter is {@code ACTIVE}
     */
    public boolean isActiveFilter() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
