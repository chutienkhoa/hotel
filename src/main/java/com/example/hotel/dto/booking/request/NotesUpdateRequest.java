package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.Size;

/**
 * Carries only the field accepted by the dedicated Reservation Notes update operation. Notes remain optional:
 * blank or {@code null} is valid.
 *
 * @param notes replacement internal operational notes
 */
public record NotesUpdateRequest(@Size(max = 5000) String notes) {}
