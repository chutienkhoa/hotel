package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contains the only client-controlled field accepted to void a PAID Payment recorded in error (wrong
 * amount, wrong method, or a duplicate entry): the reason. Voiding a Payment records that the money
 * it represents was never actually received or returned, which is distinct from
 * {@link PaymentRefundRequest} (money actually received and later handed back to the guest); the two
 * must never be confused. There is no {@code voidAmount}; a Payment is voided in full.
 */
public record PaymentVoidRequest(@NotBlank @Size(max = 1000) String reason) {}
