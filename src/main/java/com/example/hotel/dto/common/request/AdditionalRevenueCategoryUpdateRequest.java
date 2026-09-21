package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contains only the editable display fields for an Additional Revenue category. */
public record AdditionalRevenueCategoryUpdateRequest(
        @NotBlank @Size(max = 128) String name, @Size(max = 1000) String description) {

    /** Normalizes user-entered values before validation and persistence. */
    public AdditionalRevenueCategoryUpdateRequest {
        name = name == null ? null : name.trim();
        description = normalizeDescription(description);
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
