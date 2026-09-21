package com.example.hotel.dto.common.response;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One Reservation row of the monthly Excel export (one row per Reservation, never per booked room). Locale-free.
 *
 * @param reservationNumber Reservation number
 * @param source booking source
 * @param otaBookingReference external OTA booking reference, or {@code null}
 * @param guestName Guest display name (first and last name)
 * @param checkInDate planned check-in date
 * @param checkOutDate planned check-out date
 * @param roomTypeNames distinct booked RoomType names in RoomType-code order
 * @param totalAmount Reservation total in its own currency, never converted
 * @param currency Reservation currency
 * @param status Reservation status
 */
public record MonthlyReservationExportRow(
        String reservationNumber,
        BookingSource source,
        String otaBookingReference,
        String guestName,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        List<String> roomTypeNames,
        BigDecimal totalAmount,
        String currency,
        ReservationStatus status) {}
