package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the editable draft Reservation data required by the MVC edit form.
 *
 * @param bookingContactName the Reservation's own stored Booking Contact name (not the Primary Guest fallback)
 * @param bookingContactPhone the Reservation's own stored Booking Contact phone (not the Primary Guest fallback)
 * @param bookingContactEmail the Reservation's own stored Booking Contact email (not the Primary Guest fallback)
 */
public record ReservationEditResponse(
        UUID id,
        String status,
        UUID guestId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int adultCount,
        int childCount,
        BookingSource source,
        String otaBookingReference,
        String currency,
        String notes,
        List<ReservationRoomResponse> rooms,
        List<UUID> accompanyingGuestIds,
        String bookingContactName,
        String bookingContactPhone,
        String bookingContactEmail) {

    /**
     * Creates a response without Booking Contact data, for existing fixtures/tests that predate Booking Contact.
     * Production mapping always uses the canonical constructor with the stored Booking Contact snapshot.
     *
     * @param id the reservation identifier
     * @param status the current reservation status
     * @param guestId the associated Primary Guest identifier
     * @param checkInDate the planned check-in date
     * @param checkOutDate the planned check-out date
     * @param adultCount the number of adults
     * @param childCount the number of children
     * @param source the reservation's booking source
     * @param otaBookingReference the external OTA booking reference, or {@code null} for DIRECT
     * @param currency the three-letter currency code
     * @param notes the optional reservation notes
     * @param rooms the assigned-room snapshots
     * @param accompanyingGuestIds the Accompanying Guest identifiers
     */
    public ReservationEditResponse(
            UUID id,
            String status,
            UUID guestId,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int adultCount,
            int childCount,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes,
            List<ReservationRoomResponse> rooms,
            List<UUID> accompanyingGuestIds) {
        this(id, status, guestId, checkInDate, checkOutDate, adultCount, childCount, source, otaBookingReference,
                currency, notes, rooms, accompanyingGuestIds, null, null, null);
    }
}
