package com.example.hotel.dto.room.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the required free-text reason for taking a Room into MAINTENANCE or OUT_OF_ORDER, so the
 * unavailability episode recorded in Room inventory history is never left unexplained.
 *
 * @param reason the required free-text explanation
 */
public record RoomUnavailabilityRequest(
        @NotBlank(message = "{validation.room.unavailability.reason.required}")
                @Size(max = 2000, message = "{validation.room.unavailability.reason.max}")
                String reason) {}
