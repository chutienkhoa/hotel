package com.example.hotel.dto.booking.request;

import com.example.hotel.common.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dữ liệu request cho Walk-in Check-in. Source (DIRECT) và checkInDate (hotel current date) are
 * never accepted from the client; they are always assigned server-side by {@code CheckInService}.
 */
public record WalkInRequest(
        @NotNull UUID guestId,
        @NotNull LocalDate checkOutDate,
        @NotNull(message = "{validation.reservation.adultCount.required}")
                @Min(value = 1, message = "{validation.reservation.adultCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer adultCount,
        @NotNull(message = "{validation.reservation.childCount.required}")
                @Min(value = 0, message = "{validation.reservation.childCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer childCount,
        @NotBlank(message = "{validation.reservation.currency.required}")
                @Pattern(regexp = "VND|USD", message = "{validation.reservation.currency.supported}")
                String currency,
        @Size(max = 5000) String notes,
        @NotEmpty List<@Valid RoomRequest> rooms) {}
