package com.example.hotel.entity.booking;

/** Liệt kê các trạng thái trong vòng đời reservation. */
public enum ReservationStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED,
    NO_SHOW,
    CHECKED_IN,
    CHECKED_OUT;

    /**
     * Returns the approved staff-facing label for this reservation status.
     *
     * @return the status label used by Reservation Detail presentation
     */
    public String getDisplayName() {
        return switch (this) {
            case DRAFT -> "Draft";
            case CONFIRMED -> "Confirmed";
            case CANCELLED -> "Cancelled";
            case NO_SHOW -> "No-show";
            case CHECKED_IN -> "Checked in";
            case CHECKED_OUT -> "Checked out";
        };
    }
}
