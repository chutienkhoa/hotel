package com.example.hotel.entity.booking;

/** Liệt kê các trạng thái trong vòng đời reservation. */
public enum ReservationStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED,
    NO_SHOW,
    CHECKED_IN,
    CHECKED_OUT
}
