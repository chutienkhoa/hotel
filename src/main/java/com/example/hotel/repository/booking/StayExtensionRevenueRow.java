package com.example.hotel.repository.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Narrow read projection of one Stay extension line used by the Monthly Financial Report.
 *
 * @param extensionRoomId extension line identifier
 * @param originalReservationRoomId lineage (original booking line) identifier
 * @param reservationId owning Reservation identifier
 * @param fromDate first extension night
 * @param toDate exclusive end of the extension period
 * @param nightlyRate snapshot rate
 * @param amount snapshot amount
 * @param currency owning Reservation currency code
 */
public record StayExtensionRevenueRow(
        UUID extensionRoomId,
        UUID originalReservationRoomId,
        UUID reservationId,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal nightlyRate,
        BigDecimal amount,
        String currency) {}
