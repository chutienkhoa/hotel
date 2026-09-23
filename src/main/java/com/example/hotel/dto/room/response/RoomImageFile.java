package com.example.hotel.dto.room.response;

import org.springframework.core.io.Resource;

/** Carries a safely loaded Room image and response-safe metadata for authorized inline viewing. */
public record RoomImageFile(Resource resource, String contentType, String originalFilename) {}
