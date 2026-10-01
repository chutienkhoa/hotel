package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Contains the client-controlled fields accepted to create a new active Expense category.
 *
 * <p>{@code code} is normalized (trimmed and upper-cased) before Bean Validation runs, so the
 * approved {@code ^[A-Z][A-Z0-9_]*$} pattern is checked against the normalized value. The code is
 * immutable once created — it is never accepted by the update request.</p>
 */
public record ExpenseCategoryCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1000) String description) {

    /** Normalizes code, name, and description before validation and persistence. */
    public ExpenseCategoryCreateRequest {
        code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        name = name == null ? null : name.trim();
        description = normalizeDescription(description);
    }

    /**
     * Trims a description and converts a blank value to {@code null}.
     *
     * @param description raw description supplied by the form
     * @return the trimmed description, or {@code null} when absent or blank
     */
    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
