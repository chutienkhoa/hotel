package com.example.hotel.dto.common.request;

/** Captures the optional User Management list filters: username search and status. */
public class UserSearchCriteria {

    private String query;
    private String status;

    /**
     * Returns the optional case-insensitive username fragment.
     *
     * @return the username fragment, or {@code null} when absent
     */
    public String getQuery() {
        return query;
    }

    /**
     * Sets the username fragment supplied by the list form.
     *
     * @param query optional username fragment
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * Returns the selected status filter: {@code ALL}, {@code ACTIVE}, or {@code INACTIVE}.
     *
     * @return the selected status filter
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the status filter supplied by the list form.
     *
     * @param status selected status filter
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /** Trims the username fragment and converts a blank value to an absent filter. */
    public void normalize() {
        if (query != null) {
            String trimmed = query.trim();
            query = trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * Determines whether the status filter should be applied.
     *
     * @return {@code true} when the filter is {@code ACTIVE} or {@code INACTIVE}
     */
    public boolean hasStatusFilter() {
        return "ACTIVE".equalsIgnoreCase(status) || "INACTIVE".equalsIgnoreCase(status);
    }

    /**
     * Determines whether the status filter requires active accounts.
     *
     * @return {@code true} when the filter is {@code ACTIVE}
     */
    public boolean isActiveFilter() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
