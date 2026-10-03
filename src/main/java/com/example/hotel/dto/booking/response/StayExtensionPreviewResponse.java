package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only model of the Extend Stay Select and Review screens for one proposed new planned check-out date. Built by
 * {@code StayExtensionService} from the same rules {@code extend} enforces; it persists nothing and is never the
 * authority for the final Confirm, which re-validates under lock.
 *
 * @param reservationId Reservation identifier
 * @param reservationNumber Reservation number
 * @param guestName Primary Guest display name
 * @param guestCode Primary Guest code
 * @param source booking source of the Reservation
 * @param adultCount adults on the Reservation
 * @param childCount children on the Reservation
 * @param currency Reservation currency
 * @param checkInDate Reservation check-in date
 * @param currentCheckOutDate current planned check-out date
 * @param earliestNewCheckOutDate earliest valid new check-out date
 * @param requestedCheckOutDate proposed new check-out date, or {@code null} when none has been selected
 * @param state outcome of the proposed date
 * @param currentNights nights from check-in to the current planned check-out
 * @param additionalNights nights added by the proposed date; zero unless the date is valid
 * @param resultingNights nights from check-in to the resulting planned check-out
 * @param rooms one line per current room of the Stay
 * @param extensionAmount total additional room charge; {@code null} unless the date is valid
 * @param accommodationTotal Current Accommodation Total before this extension
 * @param resultingAccommodationTotal Current Accommodation Total after this extension
 * @param folio folio impact, or {@code null} when the caller may not see payment data
 * @param firstUnavailableCheckOutDate earliest checkout date within the availability window that conflicts with the
 *     Stay's rooms, or {@code null} when no date in the window conflicts
 * @param availabilityKnownUntil last checkout date covered by the availability window
 * @param overdueDays calendar days the current planned check-out is before the hotel date (0 when not overdue); describes
 *     the stay before extension and does not depend on the proposed date
 */
public record StayExtensionPreviewResponse(
        UUID reservationId,
        String reservationNumber,
        String guestName,
        String guestCode,
        BookingSource source,
        int adultCount,
        int childCount,
        String currency,
        LocalDate checkInDate,
        LocalDate currentCheckOutDate,
        LocalDate earliestNewCheckOutDate,
        LocalDate requestedCheckOutDate,
        State state,
        long currentNights,
        long additionalNights,
        long resultingNights,
        List<Room> rooms,
        BigDecimal extensionAmount,
        BigDecimal accommodationTotal,
        BigDecimal resultingAccommodationTotal,
        Folio folio,
        LocalDate firstUnavailableCheckOutDate,
        LocalDate availabilityKnownUntil,
        long overdueDays) {

    /** Outcome of the proposed new check-out date. Read-model only; never persisted. */
    public enum State {
        /** No date has been proposed yet. */
        NOT_SELECTED,
        /** The date is not after the current planned check-out, or is before hotel today. */
        INVALID_DATE,
        /** At least one current room is booked or occupied for the extension period. */
        ROOM_CONFLICT,
        /** The extension can be confirmed. */
        AVAILABLE
    }

    /**
     * Whether the proposed extension may be confirmed. Presentation convenience only: the backend re-validates on
     * Confirm.
     *
     * @return {@code true} when {@link #state()} is {@link State#AVAILABLE}
     */
    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    /**
     * One current room of the Stay.
     *
     * @param roomId current room identifier
     * @param roomNumber current room number
     * @param roomTypeName room type name, or {@code null}
     * @param nightlyRate original booked nightly rate of the room's lineage (the extension rate)
     * @param amount additional room charge for this room; {@code null} unless the date is valid
     * @param conflicted whether this room conflicts with the proposed extension period
     * @param hasPrimaryImage whether the room has a primary image to display
     */
    public record Room(
            UUID roomId,
            String roomNumber,
            String roomTypeName,
            BigDecimal nightlyRate,
            BigDecimal amount,
            boolean conflicted,
            boolean hasPrimaryImage) {}

    /**
     * Outstanding balance before and after the extension, computed from the same financial rules as check-out.
     *
     * @param totalCharges current active Charges
     * @param totalPaidPayments current applied PAID Payments
     * @param outstanding current outstanding balance
     * @param projectedOutstanding outstanding balance once the extension Charge is added
     */
    public record Folio(
            BigDecimal totalCharges, BigDecimal totalPaidPayments, BigDecimal outstanding, BigDecimal projectedOutstanding) {}
}
