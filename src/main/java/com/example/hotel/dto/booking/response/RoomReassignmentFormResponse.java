package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Data for the pre-check-in Room reassignment form: the line being replaced (with its unchanged booked rate) and
 * the check-in-ready, conflict-free replacement Rooms.
 *
 * @param reservationId Reservation identifier
 * @param reservationNumber Reservation number
 * @param currentRoomId Room being replaced
 * @param currentRoomNumber number of the Room being replaced
 * @param currentRoomTypeName RoomType of the Room being replaced, when available
 * @param checkInDate booked check-in date of the line
 * @param checkOutDate booked check-out date of the line
 * @param nightlyRate booked nightly rate, which stays unchanged
 * @param currency currency code of the Reservation
 * @param candidates eligible replacement Rooms ordered by room number
 */
public record RoomReassignmentFormResponse(
        UUID reservationId,
        String reservationNumber,
        UUID currentRoomId,
        String currentRoomNumber,
        String currentRoomTypeName,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        BigDecimal nightlyRate,
        String currency,
        List<RoomReassignmentCandidateResponse> candidates) {}
