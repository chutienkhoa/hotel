package com.example.hotel.repository.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Narrow read projection of one ReservationRoom pricing snapshot used by the Monthly Financial Report.
 *
 * @param reservationRoomId ReservationRoom identifier
 * @param reservationId owning Reservation identifier
 * @param checkInDate booked check-in date
 * @param checkOutDate booked check-out date (exclusive: the last night starts the day before)
 * @param nightlyRate booked nightly rate snapshot
 * @param totalAmount booked total snapshot
 * @param currency owning Reservation currency code
 */
public record ReservationRoomRevenueRow(
        UUID reservationRoomId,
        UUID reservationId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal nightlyRate,
        BigDecimal totalAmount,
        String currency) {}
