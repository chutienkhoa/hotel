package com.example.hotel.repository.room;

import java.time.LocalDate;

/**
 * Narrow JPQL projection of one CONFIRMED reservation still scheduled to use a Room, used to warn a
 * manager before taking that Room into MAINTENANCE or OUT_OF_ORDER without loading full Reservation
 * or ReservationRoom entities.
 *
 * @param reservationNumber the affected reservation's business number
 * @param checkInDate the reservation's planned check-in date
 * @param checkOutDate the reservation's planned check-out date
 */
public record AffectedReservationRow(String reservationNumber, LocalDate checkInDate, LocalDate checkOutDate) {}
