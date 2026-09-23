package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contains the only client-controlled field accepted to void an ACTIVE, non-ROOM Charge: the reason
 * the record was erroneous. There is no {@code voidAmount}; a Charge is voided in full.
 */
public record ChargeVoidRequest(@NotBlank @Size(max = 1000) String reason) {}
