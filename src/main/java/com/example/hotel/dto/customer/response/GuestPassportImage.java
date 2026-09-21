package com.example.hotel.dto.customer.response;

import org.springframework.core.io.Resource;

/** Carries a safely loaded passport image and response-safe metadata for authorized inline viewing. */
public record GuestPassportImage(Resource resource, String contentType, String originalName) {}
