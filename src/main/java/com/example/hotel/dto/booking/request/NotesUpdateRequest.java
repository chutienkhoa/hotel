package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.Size;

/**
 * Carries only the field accepted by the dedicated Reservation Notes update operation. Notes remain optional:
 * blank or {@code null} is valid.
 *
 * @param notes replacement internal operational notes
 */
public record NotesUpdateRequest(@Size(max = MAX_LENGTH) String notes) {

    /** The maximum number of characters Reservation notes may hold. */
    public static final int MAX_LENGTH = 5000;
}
