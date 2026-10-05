package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies an assigned-room snapshot for reservation detail output.
 *
 * @param roomId the assigned room identifier
 * @param roomNumber the assigned room number
 * @param checkInDate the room check-in date
 * @param checkOutDate the room check-out date
 * @param nightlyRate the snapshot nightly rate
 * @param totalAmount the snapshot total amount
 * @param roomTypeName the room's current Room Type name, or {@code null} when unavailable
 * @param adultCapacity the Room Type's adult capacity, or {@code null} when it is not configured
 */
public record ReservationRoomResponse(
        UUID roomId,
        String roomNumber,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal nightlyRate,
        BigDecimal totalAmount,
        String roomTypeName,
        Integer adultCapacity) {

    /**
     * Creates a room snapshot without Room Type presentation data, for callers that only need the booking snapshot.
     *
     * @param roomId the assigned room identifier
     * @param roomNumber the assigned room number
     * @param checkInDate the room check-in date
     * @param checkOutDate the room check-out date
     * @param nightlyRate the snapshot nightly rate
     * @param totalAmount the snapshot total amount
     */
    public ReservationRoomResponse(
            UUID roomId,
            String roomNumber,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BigDecimal nightlyRate,
            BigDecimal totalAmount) {
        this(roomId, roomNumber, checkInDate, checkOutDate, nightlyRate, totalAmount, null, null);
    }

    /**
     * Counts the nights of this room's booked stay from its own snapshot dates.
     *
     * @return the number of nights between the room's check-in and check-out dates
     */
    public long nights() {
        return java.time.temporal.ChronoUnit.DAYS.between(checkInDate, checkOutDate);
    }
}
