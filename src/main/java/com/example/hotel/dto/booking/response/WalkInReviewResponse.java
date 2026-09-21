package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the complete read-only preview shown before a Walk-in's atomic "Confirm & Check-in"
 * operation. Nothing has been persisted yet when this response is built.
 *
 * @param guestId the selected guest identifier
 * @param guestFullName the selected guest's display name
 * @param guestCode the selected guest's display code
 * @param passportAvailable {@code true} when at least one passport image has been uploaded for this guest
 * @param firstPassportDocumentId identifier of the Guest's oldest passport image, used to build the
 *     secure View Passport link; {@code null} when no passport image exists
 * @param checkInDate the authoritative hotel current date that will be used as check-in date
 * @param checkOutDate the staff-selected check-out date
 * @param actualCheckInPreview a preview of the check-in timestamp that would be recorded now
 * @param rooms the selected room lines with staff-entered nightly rates
 * @param totalAmount the calculated total across all selected rooms
 * @param currency the selected three-letter currency code
 */
public record WalkInReviewResponse(
        UUID guestId,
        String guestFullName,
        String guestCode,
        boolean passportAvailable,
        UUID firstPassportDocumentId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Instant actualCheckInPreview,
        List<CheckInRoomLine> rooms,
        BigDecimal totalAmount,
        String currency) {
}
