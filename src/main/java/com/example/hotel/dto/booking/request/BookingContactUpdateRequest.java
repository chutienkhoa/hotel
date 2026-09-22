package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.Size;

/**
 * Carries only the three fields accepted by the dedicated Booking Contact update operation. Every field is
 * individually optional: a Reservation is valid with all three blank or {@code null}.
 *
 * @param bookingContactName replacement Booking Contact name
 * @param bookingContactPhone replacement Booking Contact phone
 * @param bookingContactEmail replacement Booking Contact email
 */
public record BookingContactUpdateRequest(
        @Size(max = 200) String bookingContactName,
        @Size(max = 100) String bookingContactPhone,
        @Size(max = 255) String bookingContactEmail) {}
