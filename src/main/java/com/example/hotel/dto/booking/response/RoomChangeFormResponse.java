package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Supplies the read-only context shown on the Room Change form: the currently occupied room taken from its
 * open StayRoomAssignment, the Stay's planned occupancy, and the booked pricing snapshot of that room's lineage.
 *
 * @param reservationId Reservation identifier
 * @param reservationNumber Reservation display number
 * @param guestId the Reservation's primary guest identifier, or {@code null} when it has no guest
 * @param guestCode the Reservation's primary guest code, or {@code null} when it has no guest
 * @param guestFullName the Reservation's primary guest full name (first and last name), or {@code null} when none
 * @param currentRoomId the currently occupied room identifier
 * @param currentRoomNumber the currently occupied room's display number
 * @param currentRoomTypeName the currently occupied room's Room Type name
 * @param currentRoomStatus the currently occupied room's operational status
 * @param checkInDate the Reservation's check-in date
 * @param checkOutDate the Reservation's current planned check-out date
 * @param nights the number of nights between check-in and planned check-out
 * @param adultCount adult guests on the Reservation
 * @param childCount child guests on the Reservation
 * @param nightlyRate the booked nightly rate of the room's lineage, unchanged by Room Change
 * @param totalAmount the booked total of the room's lineage, unchanged by Room Change
 * @param currency the Reservation currency code
 * @param currentRoomHasImage whether the current room has a primary image to show
 * @param roomChangeOpen whether the date window currently permits Room Change
 * @param overdueDays calendar days the CHECKED_IN stay is past its planned check-out date (0 when not overdue)
 */
public record RoomChangeFormResponse(
        UUID reservationId,
        String reservationNumber,
        UUID guestId,
        String guestCode,
        String guestFullName,
        UUID currentRoomId,
        String currentRoomNumber,
        String currentRoomTypeName,
        String currentRoomStatus,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int nights,
        int adultCount,
        int childCount,
        BigDecimal nightlyRate,
        BigDecimal totalAmount,
        String currency,
        boolean currentRoomHasImage,
        boolean roomChangeOpen,
        long overdueDays) {}
