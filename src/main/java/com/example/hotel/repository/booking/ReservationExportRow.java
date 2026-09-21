package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Narrow read projection of one Reservation for the monthly Excel export (guest and totals, no rooms).
 *
 * @param id Reservation identifier, used to attach the booked room types
 * @param reservationNumber Reservation number
 * @param source booking source
 * @param otaBookingReference external OTA booking reference, or {@code null} (DIRECT)
 * @param guestFirstName Guest first name
 * @param guestLastName Guest last name
 * @param checkInDate planned check-in date
 * @param checkOutDate planned check-out date
 * @param totalAmount Reservation total in its own currency
 * @param currency Reservation currency
 * @param status Reservation status
 */
public record ReservationExportRow(
        UUID id,
        String reservationNumber,
        BookingSource source,
        String otaBookingReference,
        String guestFirstName,
        String guestLastName,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal totalAmount,
        String currency,
        ReservationStatus status) {}
