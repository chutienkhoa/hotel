package com.example.hotel.dto.customer.request;

/**
 * Captures the optional free-text filter for the Guest Management list.
 */
public class GuestSearchCriteria {

    private String query;

    /**
     * Returns the optional case-insensitive Guest search fragment.
     *
     * @return the supplied search fragment, or {@code null} when absent
     */
    public String getQuery() {
        return query;
    }

    /**
     * Sets the free-text search fragment supplied by the Guest list form.
     *
     * @param query optional Guest search fragment
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * Trims the search fragment and converts blank input to an absent filter.
     */
    public void normalizeQuery() {
        if (query != null) {
            query = query.trim();
            if (query.isEmpty()) {
                query = null;
            }
        }
    }
}
