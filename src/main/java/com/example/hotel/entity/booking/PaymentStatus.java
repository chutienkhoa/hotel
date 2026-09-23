package com.example.hotel.entity.booking;

/** Defines the approved lifecycle states for a Payment. */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
    VOIDED
}
