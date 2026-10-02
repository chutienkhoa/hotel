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
 * @param adultCount the number of adults (read-only information)
 * @param childCount the number of children (read-only information)
 * @param accompanyingGuests the Accompanying Guests (known profiles; read-only information, never a readiness input)
 * @param reservedAt the instant the Reservation was made (Booking Date)
 * @param guestDateOfBirth the associated Guest's date of birth, when available
 * @param guestPhone the associated Guest's own phone number, when available
 * @param guestEmail the associated Guest's own email address, when available
 * @param effectiveBookingContactName the Booking Contact name, or the Primary Guest's name when no Booking
 *     Contact snapshot is set
 * @param effectiveBookingContactPhone the Booking Contact phone, or the Primary Guest's phone when no Booking
 *     Contact snapshot is set
 * @param effectiveBookingContactEmail the Booking Contact email, or the Primary Guest's email when no Booking
 *     Contact snapshot is set
 * @param bookingContactFromPrimaryGuest {@code true} when no Booking Contact snapshot is set and the three
 *     effective fields above are the Primary Guest fallback, not the Reservation's own snapshot
 * @param firstPassportDocumentId identifier of the Guest's oldest passport image, used to build the secure
 *     View Passport link; {@code null} when no passport image exists
 * @param arrivalOverdueDays whole days the planned check-in is in the past (0 unless {@code timing} is LATE)
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
        ArrivalReadiness readiness,
        int adultCount,
        int childCount,
        List<AccompanyingGuestResponse> accompanyingGuests,
        Instant reservedAt,
        LocalDate guestDateOfBirth,
        String guestPhone,
        String guestEmail,
        String effectiveBookingContactName,
        String effectiveBookingContactPhone,
        String effectiveBookingContactEmail,
        boolean bookingContactFromPrimaryGuest,
        UUID firstPassportDocumentId,
        long arrivalOverdueDays) {
}
