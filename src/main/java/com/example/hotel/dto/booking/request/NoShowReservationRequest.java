package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the required free-text operational reason for marking a CONFIRMED Reservation NO_SHOW.
 *
 * @param noShowReason the required free-text explanation
 */
public record NoShowReservationRequest(
        @NotBlank(message = "{validation.reservation.noShow.reason.required}")
                @Size(max = 2000, message = "{validation.reservation.noShow.reason.max}")
                String noShowReason) {}
