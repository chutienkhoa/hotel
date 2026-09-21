package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the complete read-only data required by the Existing Reservation Check-in Review
 * page: Guest/passport, booking, and room/financial information, plus the Early/Normal/Late
 * timing classification used to decide whether Check-in may proceed.
 *
 * @param reservationId the reservation identifier
 * @param reservationNumber the external reservation number
 * @param status the current reservation status
 * @param eligibleForCheckIn {@code true} only when the derived readiness has no BLOCKER (status, timing, Stay and room state)
 * @param timing the Early/Normal/Late classification of the hotel current date
 * @param scheduledCheckInDate the reservation's planned check-in date
 * @param scheduledCheckOutDate the reservation's planned check-out date
 * @param currentHotelDate the authoritative hotel current date used for the comparison
 * @param actualCheckInPreview a preview of the check-in timestamp that would be recorded now
 * @param source the reservation's booking source
 * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
 * @param guestId the associated guest identifier
 * @param guestFullName the associated guest's display name
 * @param guestCode the associated guest's display code
 * @param guestNationality the associated guest's nationality, when available
 * @param passportAvailable {@code true} when a passport image has been uploaded for this guest
 * @param rooms the assigned-room lines
 * @param totalAmount the reservation's snapshot total amount
 * @param currency the three-letter currency code
 * @param readiness the derived, non-persisted Arrival Readiness (blockers, warnings and info)
 */
public record CheckInReviewResponse(
        UUID reservationId,
        String reservationNumber,
        String status,
        boolean eligibleForCheckIn,
        CheckInTiming timing,
        LocalDate scheduledCheckInDate,
        LocalDate scheduledCheckOutDate,
        LocalDate currentHotelDate,
        Instant actualCheckInPreview,
        BookingSource source,
        String otaBookingReference,
        UUID guestId,
        String guestFullName,
        String guestCode,
        String guestNationality,
        boolean passportAvailable,
        List<CheckInRoomLine> rooms,
        BigDecimal totalAmount,
        String currency,
        ArrivalReadiness readiness) {
}
