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
    private String arrivalDate;
    private String readiness;
    private String source;
    private String roomType;
    private String checkoutDate;
    private String status;

    /**
     * Returns the optional In-house Room Type filter: the identifier of a RoomType. Matches the Room a guest
     * currently occupies (open StayRoomAssignment), never an obsolete booked Room.
     *
     * @return the raw requested value, or {@code null} for all room types
     */
    public String getRoomType() {
        return roomType;
    }

    /**
     * Sets the optional In-house Room Type filter.
     *
     * @param roomType requested RoomType identifier, validated by the service
     */
    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    /**
     * Returns the optional Departures checkout-date filter: {@code TODAY} or {@code OVERDUE}. Departures only.
     *
     * @return the raw requested value, or {@code null} for all dates
     */
    public String getCheckoutDate() {
        return checkoutDate;
    }

    /**
     * Sets the optional Departures checkout-date filter.
     *
     * @param checkoutDate requested value, validated by the service
     */
    public void setCheckoutDate(String checkoutDate) {
        this.checkoutDate = checkoutDate;
    }

    /**
     * Returns the optional Departures status filter: {@code READY}, {@code OVERDUE} or {@code PAYMENT_REQUIRED}.
     * Departures only.
     *
     * @return the raw requested value, or {@code null} for all statuses
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the optional Departures status filter.
     *
     * @param status requested value, validated by the service
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the optional Arrivals date filter: {@code TODAY} or {@code OVERDUE}. Arrivals only.
     *
     * @return the raw requested value, or {@code null} for all dates
     */
    public String getArrivalDate() {
        return arrivalDate;
    }

    /**
     * Sets the optional Arrivals date filter.
     *
     * @param arrivalDate requested value, validated by the service
     */
    public void setArrivalDate(String arrivalDate) {
        this.arrivalDate = arrivalDate;
    }

    /**
     * Returns the optional Arrivals readiness filter, an {@code ArrivalReadinessState} name. Arrivals only.
     *
     * @return the raw requested value, or {@code null} for all statuses
     */
    public String getReadiness() {
        return readiness;
    }

    /**
     * Sets the optional Arrivals readiness filter.
     *
     * @param readiness requested value, validated by the service
     */
    public void setReadiness(String readiness) {
        this.readiness = readiness;
    }

    /**
     * Returns the optional Arrivals booking-source filter, a {@code BookingSource} name. Arrivals only.
     *
     * @return the raw requested value, or {@code null} for all sources
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the optional Arrivals booking-source filter.
     *
     * @param source requested value, validated by the service
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Tells whether any search or filter is active, so an empty result can explain that filters matched nothing.
     *
     * @return {@code true} when a search fragment or any filter is set
     */
    public boolean hasCriteria() {
        return search != null || arrivalDate != null || readiness != null || source != null || roomType != null
                || checkoutDate != null || status != null;
    }

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
        search = blankToNull(search);
        arrivalDate = blankToNull(arrivalDate);
        readiness = blankToNull(readiness);
        source = blankToNull(source);
        roomType = blankToNull(roomType);
        checkoutDate = blankToNull(checkoutDate);
        status = blankToNull(status);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
