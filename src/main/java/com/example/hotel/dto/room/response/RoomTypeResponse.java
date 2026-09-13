package com.example.hotel.dto.room.response;

import java.util.UUID;

/** Exposes the read-only RoomType fields needed by Room Management clients. */
public record RoomTypeResponse(UUID id, String code, String name) {}
