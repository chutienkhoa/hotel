package com.example.hotel.dto.room.response;

import java.time.LocalDate;
import java.util.List;

/**
 * The Housekeeping workspace read model. READY is a derived presentation group of active AVAILABLE Rooms and is
 * never persisted; issues are MAINTENANCE and OUT_OF_ORDER Rooms.
 *
 * @param hotelDate the hotel current date used for arrival semantics
 * @param needsCleaning DIRTY Rooms, urgent first
 * @param cleaning CLEANING Rooms
 * @param ready active AVAILABLE Rooms
 * @param issues MAINTENANCE and OUT_OF_ORDER Rooms
 */
public record HousekeepingWorkspaceResponse(
        LocalDate hotelDate,
        List<HousekeepingRoomResponse> needsCleaning,
        List<HousekeepingRoomResponse> cleaning,
        List<HousekeepingRoomResponse> ready,
        List<HousekeepingRoomResponse> issues) {}
