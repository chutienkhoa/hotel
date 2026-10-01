package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contains the only client-controlled field accepted to refund a paid Payment. V1 supports full
 * refund only; there is no {@code refundAmount}.
 */
public record PaymentRefundRequest(@NotBlank @Size(max = 1000) String reason) {}
