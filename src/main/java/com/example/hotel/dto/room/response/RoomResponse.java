package com.example.hotel.dto.room.response;

import java.util.UUID;

/** Exposes a room profile without accepting or returning server-controlled audit values. */
public record RoomResponse(
        UUID id,
        String roomNumber,
        RoomTypeResponse roomType,
        String floor,
        String status,
        boolean active) {}
