package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
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
        List<ReservationRoomResponse> rooms) {

    /**
     * Creates a detail response with the fixture-default guest composition (1 adult, 0 children). Production
     * mapping always uses the canonical constructor with the stored counts.
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
                checkOutDate, 1, 0, totalAmount, currency, notes, rooms);
    }
}
