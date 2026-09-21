package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Occupancy of one historical RoomType within a Monthly Occupancy Report. The RoomType is the one recorded
 * by Room inventory history for each hotel night, not the Room's current type. Locale-free.
 *
 * @param roomTypeId RoomType identifier
 * @param roomTypeCode stable RoomType code, used for deterministic ordering
 * @param roomTypeName RoomType name as stored (business data, not translated)
 * @param occupiedRoomNights occupied room-nights attributed to this type
 * @param sellableRoomNights sellable room-nights attributed to this type
 * @param occupancyRate occupied / sellable x 100 with 2 decimals (HALF_UP), or {@code null} when sellable is 0
 */
public record RoomTypeOccupancy(
        UUID roomTypeId,
        String roomTypeCode,
        String roomTypeName,
        long occupiedRoomNights,
        long sellableRoomNights,
        BigDecimal occupancyRate) {}
