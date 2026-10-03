package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The extension history and derived accommodation totals of one Reservation. The original booking total is
 * {@code Reservation.totalAmount}; the extension amount and current accommodation total are derived, never stored.
 *
 * @param originalBookingTotal Reservation.totalAmount (original booked accommodation snapshot)
 * @param extensionAmount sum of all extension line amounts
 * @param currentAccommodationTotal original booking total plus extension amount
 * @param events extension events in chain order
 */
public record StayExtensionSummaryResponse(
        BigDecimal originalBookingTotal,
        BigDecimal extensionAmount,
        BigDecimal currentAccommodationTotal,
        List<Event> events) {

    /**
     * One extension event.
     *
     * @param sequenceNo position in the chain
     * @param previousCheckOutDate planned check-out before
     * @param newCheckOutDate planned check-out after
     * @param lines per-room snapshots
     */
    public record Event(int sequenceNo, LocalDate previousCheckOutDate, LocalDate newCheckOutDate, List<Line> lines) {}

    /**
     * One per-room snapshot of an event.
     *
     * @param roomNumber room actually occupied at the time of the extension
     * @param nightlyRate rate snapshot
     * @param nights added nights
     * @param amount amount snapshot
     * @param roomId identifier of the room occupied at the time of the extension, used to link to its Room Detail
     */
    public record Line(String roomNumber, BigDecimal nightlyRate, long nights, BigDecimal amount, UUID roomId) {

        /**
         * Creates an extension line without the room identifier, for existing fixtures that predate room links.
         *
         * @param roomNumber room actually occupied at the time of the extension
         * @param nightlyRate rate snapshot
         * @param nights added nights
         * @param amount amount snapshot
         */
        public Line(String roomNumber, BigDecimal nightlyRate, long nights, BigDecimal amount) {
            this(roomNumber, nightlyRate, nights, amount, null);
        }
    }
}
