package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contains the operator-provided reason required to void recorded Additional Revenue. */
public record AdditionalRevenueVoidRequest(@NotBlank @Size(max = 1000) String voidReason) {}
