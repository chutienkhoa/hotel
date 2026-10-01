package com.example.hotel.dto.room.response;

import java.time.LocalDate;

/**
 * Describes one CONFIRMED reservation still scheduled to use a Room, shown as an informational
 * warning before a manager takes that Room into MAINTENANCE or OUT_OF_ORDER. No guest information is
 * included.
 *
 * @param reservationNumber the affected reservation's business number
 * @param checkInDate the reservation's planned check-in date
 * @param checkOutDate the reservation's planned check-out date
 */
public record AffectedReservationResponse(String reservationNumber, LocalDate checkInDate, LocalDate checkOutDate) {}
