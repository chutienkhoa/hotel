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
 * @param contactPhone the effective Booking Contact phone (the Reservation's own snapshot, or the Primary Guest
 *     fallback when no snapshot is set), or {@code null} when neither is available
 * @param nights number of nights between the planned check-in and check-out dates
 * @param overdueDays whole days the planned check-in date is before the hotel date, {@code 0} when not overdue
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
        boolean housekeepingRelated,
        String contactPhone,
        long nights,
        long overdueDays) {

    /**
     * Creates an arrival row without stay-length or overdue-day figures (for callers that do not display them).
     *
     * @param reservationId Reservation identifier
     * @param reservationNumber Reservation number
     * @param guestName Guest full name, or {@code null}
     * @param guestCode Guest code
     * @param source booking source
     * @param otaBookingReference external booking reference, or {@code null}
     * @param checkInDate planned check-in date
     * @param overdue whether the check-in date has passed
     * @param needsAttention whether the arrival needs attention
     * @param readiness derived Arrival Readiness
     * @param rooms booked Rooms
     * @param housekeepingRelated whether a Room is DIRTY or CLEANING
     * @param contactPhone effective Booking Contact phone, or {@code null}
     */
    public FrontDeskArrivalRow(
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
            boolean housekeepingRelated,
            String contactPhone) {
        this(reservationId, reservationNumber, guestName, guestCode, source, otaBookingReference, checkInDate,
                overdue, needsAttention, readiness, rooms, housekeepingRelated, contactPhone, 0L, 0L);
    }
}
