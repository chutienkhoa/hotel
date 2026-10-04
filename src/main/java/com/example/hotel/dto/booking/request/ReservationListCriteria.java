package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Captures the optional server-side filters of the Task33 Reservation List: one unified search fragment, a Stay date
 * range, a Reservation status, a booking source and the requested sort. It is separate from
 * {@link ReservationSearchCriteria}, which other screens (such as Check-out) continue to use unchanged.
 */
public class ReservationListCriteria {

    private String search;
    private ReservationStatus status;
    private BookingSource source;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate stayFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate stayTo;
    private String sort;
    private String dir;

    /** Returns the optional unified search fragment. */
    public String getSearch() {
        return search;
    }

    /** Sets the unified search fragment supplied by the list form. */
    public void setSearch(String search) {
        this.search = search;
    }

    /** Returns the optional exact Reservation status. */
    public ReservationStatus getStatus() {
        return status;
    }

    /** Sets the optional exact Reservation status. */
    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    /** Returns the optional exact booking source. */
    public BookingSource getSource() {
        return source;
    }

    /** Sets the optional exact booking source. */
    public void setSource(BookingSource source) {
        this.source = source;
    }

    /** Returns the start (inclusive) of the selected half-open Stay date range, or {@code null}. */
    public LocalDate getStayFrom() {
        return stayFrom;
    }

    /** Sets the start of the selected Stay date range. */
    public void setStayFrom(LocalDate stayFrom) {
        this.stayFrom = stayFrom;
    }

    /** Returns the end (exclusive) of the selected half-open Stay date range, or {@code null}. */
    public LocalDate getStayTo() {
        return stayTo;
    }

    /** Sets the end of the selected Stay date range. */
    public void setStayTo(LocalDate stayTo) {
        this.stayTo = stayTo;
    }

    /** Returns the requested public sort key, which is validated against a whitelist before use. */
    public String getSort() {
        return sort;
    }

    /** Sets the requested public sort key. */
    public void setSort(String sort) {
        this.sort = sort;
    }

    /** Returns the requested sort direction, which is validated before use. */
    public String getDir() {
        return dir;
    }

    /** Sets the requested sort direction. */
    public void setDir(String dir) {
        this.dir = dir;
    }

    /** Trims the search fragment and converts blank input to absent input. */
    public void normalize() {
        if (search != null) {
            String trimmed = search.trim();
            search = trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * Tells whether any filter is active, which distinguishes "no Reservations exist" from "no Reservation matches".
     *
     * @return {@code true} when a search, status, source or Stay date filter is present
     */
    public boolean hasCriteria() {
        return search != null || status != null || source != null || stayFrom != null || stayTo != null;
    }
}
