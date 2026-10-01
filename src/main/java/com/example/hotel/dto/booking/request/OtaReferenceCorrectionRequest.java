package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries only the reference accepted by the dedicated confirmed-reservation OTA correction operation.
 *
 * @param otaBookingReference corrected external OTA booking reference
 */
public record OtaReferenceCorrectionRequest(
        @NotBlank(message = "{validation.reservation.otaReference.required}")
                @Size(max = 255, message = "{validation.reservation.otaReference.max}")
                String otaBookingReference) {}
