package com.example.hotel.dto.booking.response;

import java.time.Instant;

/**
 * Supplies one chronological Room History line for a Stay, covering both the initial Check-in
 * assignment and every later Room Change, in lineage-then-time order.
 *
 * @param roomNumber the physical room occupied during this interval
 * @param assignedFrom the instant this interval started
 * @param assignedTo the instant this interval ended, or {@code null} when it is the current room
 * @param reasonDisplay "Initial Check-in" for the seeded assignment, otherwise the Room Change reason
 * @param changedByUsername the username attributed to this assignment
 */
public record RoomHistoryLineResponse(
        String roomNumber, Instant assignedFrom, Instant assignedTo, String reasonDisplay, String changedByUsername) {}
