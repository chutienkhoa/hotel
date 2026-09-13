package com.example.hotel.dto.booking.request;

import com.example.hotel.entity.booking.ChargeType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Contains the client-controlled fields accepted when recording a Charge v1 entry. */
public record ChargeCreateRequest(
        @NotNull ChargeType type,
        @Size(max = 1000) String description,
        @Positive BigDecimal quantity,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal unitPrice,
        @NotNull @Positive BigDecimal amount) {

    /**
     * Confirms quantity and unit price are supplied together or both omitted.
     *
     * @return {@code true} if both descriptive values are either present or absent
     */
    @AssertTrue(message = "quantity and unitPrice must both be present or absent")
    public boolean isQuantityAndUnitPricePaired() {
        return (quantity == null) == (unitPrice == null);
    }
}
