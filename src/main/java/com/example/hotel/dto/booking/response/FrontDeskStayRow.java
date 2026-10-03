package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One checked-in Stay in the Front Desk Departures or In-house worklist. Readiness and attention are derived.
 *
 * @param reservationId Reservation identifier (the key of the Check-out and Folio routes)
 * @param reservationNumber Reservation number
 * @param guestName Guest full name, or {@code null} when not available
 * @param guestCode Guest code
 * @param rooms CURRENT Rooms from open StayRoomAssignments
 * @param actualCheckInAt actual check-in instant
 * @param plannedCheckOutDate planned check-out date
 * @param overdueDays whole days the planned check-out is before the hotel date (0 when not overdue)
 * @param overdue {@code true} when the planned check-out date has passed
 * @param paymentRequired {@code true} when the outstanding balance is not zero
 * @param needsAttention {@code true} when overdue or payment is required
 * @param outstanding outstanding amount, only for users allowed to see money, otherwise {@code null}
 * @param currency Reservation currency
 * @param source booking source of the Reservation
 * @param nights full length of the stay in nights, from the Reservation check-in date to its check-out date
 */
public record FrontDeskStayRow(
        UUID reservationId,
        String reservationNumber,
        String guestName,
        String guestCode,
        List<FrontDeskRoomResponse> rooms,
        Instant actualCheckInAt,
        LocalDate plannedCheckOutDate,
        boolean overdue,
        long overdueDays,
        boolean paymentRequired,
        boolean needsAttention,
        BigDecimal outstanding,
        String currency,
        BookingSource source,
        long nights) {}
