package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.CancellationReasonCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the reservation fields required by the reservation detail page.
 *
 * @param id the reservation identifier
 * @param reservationNumber the external reservation number
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param status the current reservation status
 * @param source the reservation's booking source
 * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
 * @param checkInDate the planned check-in date
 * @param checkOutDate the planned check-out date
 * @param adultCount the number of adults
 * @param childCount the number of children
 * @param totalAmount the snapshot total amount
 * @param currency the three-letter currency code
 * @param notes the optional reservation notes
 * @param rooms the assigned-room snapshots
 * @param accompanyingGuests the Accompanying Guests (known Guest profiles; not the full physical party)
 * @param effectiveBookingContactName the Booking Contact name, or the Primary Guest's name when no Booking Contact
 *     snapshot is set
 * @param effectiveBookingContactPhone the Booking Contact phone, or the Primary Guest's phone when no Booking
 *     Contact snapshot is set
 * @param effectiveBookingContactEmail the Booking Contact email, or the Primary Guest's email when no Booking
 *     Contact snapshot is set
 * @param bookingContactFromPrimaryGuest {@code true} when no Booking Contact snapshot is set and the three
 *     effective fields above are the Primary Guest fallback, not the Reservation's own snapshot
 * @param cancellationReasonCode the structured cancellation reason, or {@code null} when not CANCELLED or a
 *     historical CANCELLED reservation recorded before this feature existed
 * @param cancellationReasonDetail the optional cancellation reason detail, or {@code null}
 * @param noShowReason the required no-show operational reason, or {@code null} when not NO_SHOW or a
 *     historical NO_SHOW reservation recorded before this feature existed
 */
public record ReservationDetailResponse(
        UUID id,
        String reservationNumber,
        UUID guestId,
        String guestCode,
        String status,
        BookingSource source,
        String otaBookingReference,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int adultCount,
        int childCount,
        BigDecimal totalAmount,
        String currency,
        String notes,
        List<ReservationRoomResponse> rooms,
        List<AccompanyingGuestResponse> accompanyingGuests,
        String effectiveBookingContactName,
        String effectiveBookingContactPhone,
        String effectiveBookingContactEmail,
        boolean bookingContactFromPrimaryGuest,
        CancellationReasonCode cancellationReasonCode,
        String cancellationReasonDetail,
        String noShowReason) {

    /**
     * Creates a detail response without cancellation/no-show reason data, for existing fixtures/tests that
     * predate this feature. Production mapping always uses the canonical constructor with the stored reasons.
     *
     * @param id the reservation identifier
     * @param reservationNumber the external reservation number
     * @param guestId the associated guest identifier
     * @param guestCode the associated guest's display code
     * @param status the current reservation status
     * @param source the reservation's booking source
     * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
     * @param checkInDate the planned check-in date
     * @param checkOutDate the planned check-out date
     * @param adultCount the number of adults
     * @param childCount the number of children
     * @param totalAmount the snapshot total amount
     * @param currency the three-letter currency code
     * @param notes the optional reservation notes
     * @param rooms the assigned-room snapshots
     * @param accompanyingGuests the Accompanying Guests
     * @param effectiveBookingContactName the effective Booking Contact name
     * @param effectiveBookingContactPhone the effective Booking Contact phone
     * @param effectiveBookingContactEmail the effective Booking Contact email
     * @param bookingContactFromPrimaryGuest whether the effective contact is the Primary Guest fallback
     */
    public ReservationDetailResponse(
            UUID id,
            String reservationNumber,
            UUID guestId,
            String guestCode,
            String status,
            BookingSource source,
            String otaBookingReference,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BigDecimal totalAmount,
            String currency,
            String notes,
            List<ReservationRoomResponse> rooms,
            List<AccompanyingGuestResponse> accompanyingGuests,
            String effectiveBookingContactName,
            String effectiveBookingContactPhone,
            String effectiveBookingContactEmail,
            boolean bookingContactFromPrimaryGuest) {
        this(id, reservationNumber, guestId, guestCode, status, source, otaBookingReference, checkInDate,
                checkOutDate, adultCount, childCount, totalAmount, currency, notes, rooms, accompanyingGuests,
                effectiveBookingContactName, effectiveBookingContactPhone, effectiveBookingContactEmail,
                bookingContactFromPrimaryGuest, null, null, null);
    }

    /**
     * Creates a detail response without Booking Contact data, for existing fixtures/tests that predate Booking
     * Contact. Production mapping always uses the canonical constructor with the resolved effective contact.
     *
     * @param id the reservation identifier
     * @param reservationNumber the external reservation number
     * @param guestId the associated guest identifier
     * @param guestCode the associated guest's display code
     * @param status the current reservation status
     * @param source the reservation's booking source
     * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
     * @param checkInDate the planned check-in date
     * @param checkOutDate the planned check-out date
     * @param adultCount the number of adults
     * @param childCount the number of children
     * @param totalAmount the snapshot total amount
     * @param currency the three-letter currency code
     * @param notes the optional reservation notes
     * @param rooms the assigned-room snapshots
     * @param accompanyingGuests the Accompanying Guests
     */
    public ReservationDetailResponse(
            UUID id,
            String reservationNumber,
            UUID guestId,
            String guestCode,
            String status,
            BookingSource source,
            String otaBookingReference,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BigDecimal totalAmount,
            String currency,
            String notes,
            List<ReservationRoomResponse> rooms,
            List<AccompanyingGuestResponse> accompanyingGuests) {
        this(id, reservationNumber, guestId, guestCode, status, source, otaBookingReference, checkInDate,
                checkOutDate, adultCount, childCount, totalAmount, currency, notes, rooms, accompanyingGuests,
                null, null, null, false);
    }

    /**
     * Creates a detail response with the fixture-default guest composition (1 adult, 0 children), no Accompanying
     * Guests and no Booking Contact data. Production mapping always uses the canonical constructor with the stored
     * counts and the resolved effective contact.
     *
     * @param id the reservation identifier
     * @param reservationNumber the external reservation number
     * @param guestId the associated guest identifier
     * @param guestCode the associated guest's display code
     * @param status the current reservation status
     * @param source the reservation's booking source
     * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
     * @param checkInDate the planned check-in date
     * @param checkOutDate the planned check-out date
     * @param totalAmount the snapshot total amount
     * @param currency the three-letter currency code
     * @param notes the optional reservation notes
     * @param rooms the assigned-room snapshots
     */
    public ReservationDetailResponse(
            UUID id,
            String reservationNumber,
            UUID guestId,
            String guestCode,
            String status,
            BookingSource source,
            String otaBookingReference,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BigDecimal totalAmount,
            String currency,
            String notes,
            List<ReservationRoomResponse> rooms) {
        this(id, reservationNumber, guestId, guestCode, status, source, otaBookingReference, checkInDate,
                checkOutDate, 1, 0, totalAmount, currency, notes, rooms, List.of());
    }
}
