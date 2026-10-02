package com.example.hotel.dto.booking.request;

/**
 * Captures the single optional Front Desk search term and the requested column sort for one
 * Arrivals, Departures or In-house request.
 *
 * <p>Front Desk loads exactly one view per request (§65), so one instance always describes only
 * that view's current search/sort state; it narrows the view's already business-scoped result set
 * and never changes which Reservations/Stays qualify.</p>
 */
public class FrontDeskSearchCriteria {

    private String search;
    private String sort;
    private String dir;

    /**
     * Returns the optional case-insensitive search fragment.
     *
     * @return the supplied search fragment, or {@code null} when absent
     */
    public String getSearch() {
        return search;
    }

    /**
     * Sets the search fragment supplied by the Front Desk search form.
     *
     * @param search optional search fragment
     */
    public void setSearch(String search) {
        this.search = search;
    }

    /**
     * Returns the requested public sort key, which is validated against the view's own whitelist
     * before use.
     *
     * @return the requested sort key, or {@code null}
     */
    public String getSort() {
        return sort;
    }

    /**
     * Sets the requested public sort key.
     *
     * @param sort requested sort key
     */
    public void setSort(String sort) {
        this.sort = sort;
    }

    /**
     * Returns the requested sort direction, which is validated before use.
     *
     * @return the requested direction, or {@code null}
     */
    public String getDir() {
        return dir;
    }

    /**
     * Sets the requested sort direction.
     *
     * @param dir requested sort direction
     */
    public void setDir(String dir) {
        this.dir = dir;
    }

    /**
     * Trims the search fragment and converts blank input to an absent filter.
     */
    public void normalize() {
        if (search == null) {
            return;
        }
        String trimmed = search.trim();
        search = trimmed.isEmpty() ? null : trimmed;
    }
}
