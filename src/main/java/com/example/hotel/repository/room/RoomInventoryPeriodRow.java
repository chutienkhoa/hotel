package com.example.hotel.repository.room;

import com.example.hotel.entity.room.RoomUnavailableReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Narrow read projection of one RoomInventoryPeriod used to expand sellable hotel nights.
 *
 * @param roomId Room the period describes
 * @param roomTypeId RoomType effective during the period
 * @param roomTypeCode stable RoomType code
 * @param roomTypeName RoomType name as stored
 * @param unavailableReason reason the Room is not sellable, or {@code null} when sellable
 * @param effectiveFrom real Instant the period started
 * @param effectiveTo real Instant the period ended, or {@code null} while open
 */
public record RoomInventoryPeriodRow(
        UUID roomId,
        UUID roomTypeId,
        String roomTypeCode,
        String roomTypeName,
        RoomUnavailableReason unavailableReason,
        Instant effectiveFrom,
        Instant effectiveTo) {}
