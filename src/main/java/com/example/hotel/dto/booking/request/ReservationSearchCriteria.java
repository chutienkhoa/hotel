package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;

/**
 * Captures optional server-side filters for the reservation list.
 */
public class ReservationSearchCriteria {

    private String reservationNumber;
    private String guest;
    private String room;
    private BookingSource source;
    private String otaBookingReference;
    private ReservationStatus status;
    private LocalDate checkInFrom;
    private LocalDate checkInTo;
    private LocalDate checkOutFrom;
    private LocalDate checkOutTo;

    /** Returns the optional case-insensitive Reservation-number fragment. */
    public String getReservationNumber() {
        return reservationNumber;
    }

    /** Sets the Reservation-number fragment supplied by the list form. */
    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }

    /** Returns the optional case-insensitive Guest first/last name fragment. */
    public String getGuest() {
        return guest;
    }

    /** Sets the Guest name fragment supplied by the list form. */
    public void setGuest(String guest) {
        this.guest = guest;
    }

    /** Returns the optional case-insensitive Room-number fragment. */
    public String getRoom() {
        return room;
    }

    /** Sets the Room-number fragment supplied by the list form. */
    public void setRoom(String room) {
        this.room = room;
    }

    /** Returns the optional exact booking source. */
    public BookingSource getSource() {
        return source;
    }

    /** Sets the optional exact booking source. */
    public void setSource(BookingSource source) {
        this.source = source;
    }

    /** Returns the optional case-insensitive OTA booking reference fragment. */
    public String getOtaBookingReference() {
        return otaBookingReference;
    }

    /** Sets the OTA booking reference fragment supplied by the list form. */
    public void setOtaBookingReference(String otaBookingReference) {
        this.otaBookingReference = otaBookingReference;
    }

    /** Returns the optional exact Reservation status. */
    public ReservationStatus getStatus() {
        return status;
    }

    /** Sets the optional exact Reservation status. */
    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    /** Returns the inclusive planned check-in lower bound. */
    public LocalDate getCheckInFrom() {
        return checkInFrom;
    }

    /** Sets the inclusive planned check-in lower bound. */
    public void setCheckInFrom(LocalDate checkInFrom) {
        this.checkInFrom = checkInFrom;
    }

    /** Returns the inclusive planned check-in upper bound. */
    public LocalDate getCheckInTo() {
        return checkInTo;
    }

    /** Sets the inclusive planned check-in upper bound. */
    public void setCheckInTo(LocalDate checkInTo) {
        this.checkInTo = checkInTo;
    }

    /** Returns the inclusive planned check-out lower bound. */
    public LocalDate getCheckOutFrom() {
        return checkOutFrom;
    }

    /** Sets the inclusive planned check-out lower bound. */
    public void setCheckOutFrom(LocalDate checkOutFrom) {
        this.checkOutFrom = checkOutFrom;
    }

    /** Returns the inclusive planned check-out upper bound. */
    public LocalDate getCheckOutTo() {
        return checkOutTo;
    }

    /** Sets the inclusive planned check-out upper bound. */
    public void setCheckOutTo(LocalDate checkOutTo) {
        this.checkOutTo = checkOutTo;
    }

    /** Trims the optional Reservation-number fragment and converts blank input to absent input. */
    public void normalizeReservationNumber() {
        reservationNumber = normalizeFragment(reservationNumber);
    }

    /** Trims the optional Guest-name fragment and converts blank input to absent input. */
    public void normalizeGuest() {
        guest = normalizeFragment(guest);
    }

    /** Trims the optional Room-number fragment and converts blank input to absent input. */
    public void normalizeRoom() {
        room = normalizeFragment(room);
    }

    /** Trims the optional OTA booking reference fragment and converts blank input to absent input. */
    public void normalizeOtaBookingReference() {
        otaBookingReference = normalizeFragment(otaBookingReference);
    }

    /**
     * Trims a submitted filter fragment and converts blank input to absent input.
     *
     * @param value submitted filter fragment
     * @return the trimmed fragment, or {@code null} when absent or blank
     */
    private String normalizeFragment(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sort;
    private String dir;

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

    private String currentRoom;

    /** Returns the optional current-room fragment matched against open Stay room assignments. */
    public String getCurrentRoom() {
        return currentRoom;
    }

    /** Sets the current-room fragment; used by Check-out, never bound from the browser form. */
    public void setCurrentRoom(String currentRoom) {
        this.currentRoom = currentRoom;
    }
}
