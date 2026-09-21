package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One pending arrival (one Reservation) in the Front Desk worklist. Attention and readiness are derived, never stored.
 *
 * @param reservationId Reservation identifier (also the Check-in Review key)
 * @param reservationNumber Reservation number
 * @param guestName Guest full name, or {@code null} when not available
 * @param guestCode Guest code
 * @param source booking source
 * @param otaBookingReference external booking reference, or {@code null}
 * @param checkInDate planned check-in date
 * @param overdue {@code true} when the check-in date has passed
 * @param needsAttention {@code true} for an overdue arrival or one with an Arrival Readiness blocker
 * @param readiness derived Arrival Readiness
 * @param rooms every booked Room, each with its own blocker if any
 * @param housekeepingRelated {@code true} when a Room is DIRTY or CLEANING
 */
public record FrontDeskArrivalRow(
        UUID reservationId,
        String reservationNumber,
        String guestName,
        String guestCode,
        BookingSource source,
        String otaBookingReference,
        LocalDate checkInDate,
        boolean overdue,
        boolean needsAttention,
        ArrivalReadiness readiness,
        List<FrontDeskRoomResponse> rooms,
        boolean housekeepingRelated) {}
