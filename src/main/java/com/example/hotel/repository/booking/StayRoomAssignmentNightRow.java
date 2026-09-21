package com.example.hotel.repository.booking;

import java.time.Instant;
import java.util.UUID;

/**
 * Narrow read projection of one StayRoomAssignment used to expand occupied hotel nights.
 *
 * @param lineageId {@code original_reservation_room_id}, the occupancy lineage
 * @param roomId physical Room occupied during the interval
 * @param assignedFrom real Instant the interval started
 * @param assignedTo real Instant the interval ended, or {@code null} while open
 */
public record StayRoomAssignmentNightRow(UUID lineageId, UUID roomId, Instant assignedFrom, Instant assignedTo) {}
