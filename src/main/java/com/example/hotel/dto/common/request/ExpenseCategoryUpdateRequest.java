package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contains the only client-controlled fields accepted to update an existing Expense category.
 *
 * <p>The category code is intentionally absent: it is immutable after creation and must never be
 * changed through this request.</p>
 */
public record ExpenseCategoryUpdateRequest(
        @NotBlank @Size(max = 128) String name, @Size(max = 1000) String description) {

    /** Normalizes name and description before validation and persistence. */
    public ExpenseCategoryUpdateRequest {
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
