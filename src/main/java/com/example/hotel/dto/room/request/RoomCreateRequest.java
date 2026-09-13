package com.example.hotel.dto.room.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Contains the mutable room-profile fields accepted when creating a room. */
public record RoomCreateRequest(
        @NotBlank @Size(max = 64) String roomNumber,
        @NotNull UUID roomTypeId,
        @Size(max = 32) String floor) {}
