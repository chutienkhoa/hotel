package com.example.hotel.dto.room.response;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One Room as shown in the Housekeeping workspace. The arrival kind is a presentation key
 * ({@code TODAY}, {@code TOMORROW}, {@code DATE} or {@code NONE}); urgency is derived, never stored.
 *
 * @param id Room identifier
 * @param roomNumber Room number
 * @param roomTypeName RoomType name
 * @param floor Room floor, if recorded
 * @param status current RoomStatus name
 * @param nextArrivalDate earliest upcoming arrival date, or {@code null}
 * @param arrivalKind how the arrival date relates to the hotel current date
 * @param urgent {@code true} for a DIRTY Room with an arrival today
 */
public record HousekeepingRoomResponse(
        UUID id,
        String roomNumber,
        String roomTypeName,
        String floor,
        String status,
        LocalDate nextArrivalDate,
        String arrivalKind,
        boolean urgent) {}
