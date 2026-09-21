package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * An Accompanying Guest (a known reusable Guest profile) shown on a Reservation. It is not the full physical party.
 * Like the Primary Guest in booking and check-in views it is identified by Guest Code only; no Guest profile data is
 * exposed to users who only hold booking or check-in permissions.
 *
 * @param guestId Guest identifier
 * @param guestCode Guest code
 */
public record AccompanyingGuestResponse(UUID guestId, String guestCode) {}
