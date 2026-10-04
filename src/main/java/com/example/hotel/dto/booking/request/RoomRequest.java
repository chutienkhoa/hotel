package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Dữ liệu một phòng và giá mỗi đêm trong request tạo reservation. */
public record RoomRequest(
        @NotNull(message = "{validation.reservation.room.required}") UUID roomId,
        @NotNull(message = "{validation.reservation.nightlyRate.positive}")
        @DecimalMin(value = "0", inclusive = false, message = "{validation.reservation.nightlyRate.positive}")
                BigDecimal nightlyRate) {}
