package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** Client-controlled fields accepted to create an active Additional Revenue category. */
public record AdditionalRevenueCategoryCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1000) String description) {

    /** Normalizes user-entered values before validation and persistence. */
    public AdditionalRevenueCategoryCreateRequest {
        code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
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
