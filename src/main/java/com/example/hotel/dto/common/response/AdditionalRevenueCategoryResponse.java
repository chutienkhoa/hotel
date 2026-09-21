package com.example.hotel.dto.common.response;

import java.util.UUID;

/** Exposes Additional Revenue category reference data safely to MVC pages. */
public record AdditionalRevenueCategoryResponse(
        UUID id, String code, String name, String description, boolean active) {}
