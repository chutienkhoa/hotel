package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the read-only Checkout Complete screen of one CHECKED_OUT Reservation. The rooms are the
 * {@code StayRoomAssignment} rows closed by the checkout (the rooms the guest actually left), never the original
 * {@code ReservationRoom} booking, and each carries the Room's CURRENT status as stored by the backend.
 *
 * @param reservationId the reservation identifier
 * @param reservationNumber the external reservation number
 * @param status the reservation status (always CHECKED_OUT for this screen)
 * @param stayStatus the Stay status (CHECKED_OUT once the Stay is closed)
 * @param guestId the associated guest identifier
 * @param guestCode the associated guest's display code
 * @param rooms the rooms released by the checkout
 * @param checkInDate the reservation's check-in date
 * @param checkOutDate the reservation's check-out date
 * @param actualCheckInAt the Stay's recorded actual check-in time
 * @param actualCheckOutAt the Stay's recorded actual check-out time
 * @param checkedOutBy username of the actor recorded by the CHECK_OUT audit entry, or {@code null} when unavailable
 * @param adultCount the Reservation's adult count
 * @param childCount the Reservation's child count
 */
public record CheckOutCompleteResponse(
        UUID reservationId,
        String reservationNumber,
        String status,
        String stayStatus,
        UUID guestId,
        String guestCode,
        List<CheckedOutRoom> rooms,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Instant actualCheckInAt,
        Instant actualCheckOutAt,
        String checkedOutBy,
        int adultCount,
        int childCount) {

    /**
     * Supplies one room released by the checkout.
     *
     * @param roomId the room identifier
     * @param roomNumber the room display number
     * @param roomTypeName the Room Type name, or {@code null} when unresolved
     * @param roomStatus the Room's current status, or {@code null} when unresolved
     */
    public record CheckedOutRoom(UUID roomId, String roomNumber, String roomTypeName, String roomStatus) {}

    /**
     * Returns the number of nights between the check-in and check-out dates.
     *
     * @return the nights, or {@code 0} when a date is missing
     */
    public long nights() {
        return checkInDate == null || checkOutDate == null
                ? 0L
                : java.time.temporal.ChronoUnit.DAYS.between(checkInDate, checkOutDate);
    }

    /**
     * Returns every released room number, so a multi-room Stay is never collapsed into one room.
     *
     * @return the comma-separated room numbers
     */
    public String roomNumbers() {
        return rooms.stream().map(CheckedOutRoom::roomNumber).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Returns the distinct Room Type names of the released rooms.
     *
     * @return the comma-separated Room Type names, or an empty string when none resolved
     */
    public String roomTypeLabel() {
        return rooms.stream()
                .map(CheckedOutRoom::roomTypeName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
