package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Dữ liệu một phòng và giá mỗi đêm trong request tạo reservation. */
public record RoomRequest(
        @NotNull UUID roomId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal nightlyRate) {}
