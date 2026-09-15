package com.example.hotel.entity.booking;

/** Liệt kê các nguồn tạo reservation được hệ thống nhận diện. */
public enum BookingSource {
    DIRECT,
    AGODA,
    BOOKING_COM,
    AIRBNB;

    /**
     * Returns the approved staff-facing label for this reservation source.
     *
     * @return the source label used by Reservation creation UI
     */
    public String getDisplayName() {
        return switch (this) {
            case DIRECT -> "Direct";
            case AGODA -> "Agoda";
            case BOOKING_COM -> "Booking.com";
            case AIRBNB -> "Airbnb";
        };
    }
}
