package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Supplies the editable draft Reservation data required by the MVC edit form. */
public record ReservationEditResponse(
        UUID id,
        String status,
        UUID guestId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BookingSource source,
        String otaBookingReference,
        String currency,
        String notes,
        List<ReservationRoomResponse> rooms) {}
