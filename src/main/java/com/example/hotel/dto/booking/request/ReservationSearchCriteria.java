package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;

/**
 * Captures optional server-side filters for the reservation list.
 */
public class ReservationSearchCriteria {

    private String reservationNumber;
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
        if (reservationNumber != null) {
            reservationNumber = reservationNumber.trim();
            if (reservationNumber.isEmpty()) {
                reservationNumber = null;
            }
        }
    }
}
