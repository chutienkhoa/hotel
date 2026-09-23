package com.example.hotel.dto.room.response;

import java.util.UUID;

/**
 * Exposes only safe Room image metadata for server-rendered Room Management pages.
 *
 * @param id image identifier, used to build its secure view/remove/set-primary routes
 * @param originalFilename original upload filename retained only as display metadata
 * @param primary whether this image is the Room's current primary image
 */
public record RoomImageResponse(UUID id, String originalFilename, boolean primary) {}
