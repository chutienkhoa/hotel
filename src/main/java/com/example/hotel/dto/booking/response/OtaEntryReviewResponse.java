package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the complete read-only preview shown before an OTA Booking Not Entered reservation is created. Nothing
 * has been persisted when this response is built.
 *
 * @param guestId the selected guest identifier
 * @param guestFullName the selected guest's display name
 * @param guestCode the selected guest's display code
 * @param passportAvailable {@code true} when at least one passport image has been uploaded for this guest
 * @param firstPassportDocumentId identifier of the Guest's oldest passport image, or {@code null} when none exists
 * @param source the OTA booking source (never DIRECT)
 * @param otaBookingReference the staff-entered OTA booking reference
 * @param checkInDate the staff-entered OTA check-in date
 * @param checkOutDate the staff-entered OTA check-out date
 * @param rooms the selected room lines with staff-entered nightly rates
 * @param totalAmount the calculated total across all selected rooms
 * @param currency the reservation currency code
 */
public record OtaEntryReviewResponse(
        UUID guestId,
        String guestFullName,
        String guestCode,
        boolean passportAvailable,
        UUID firstPassportDocumentId,
        BookingSource source,
        String otaBookingReference,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        List<CheckInRoomLine> rooms,
        BigDecimal totalAmount,
        String currency) {
}
