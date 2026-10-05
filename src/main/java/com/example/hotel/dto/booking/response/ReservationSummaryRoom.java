package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One room shown in the Reservation Detail summary strip, taken from the source that is correct for the Reservation's
 * state (booked rooms before check-in, current assignments while checked in, final assignments after check-out).
 *
 * @param roomId the room identifier, used to link to Room Detail
 * @param roomNumber the room's display number
 * @param roomTypeName the room's Room Type name, or {@code null} when it is not resolved
 */
public record ReservationSummaryRoom(UUID roomId, String roomNumber, String roomTypeName) {}
