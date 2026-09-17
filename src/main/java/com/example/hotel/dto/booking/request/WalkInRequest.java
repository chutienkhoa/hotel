package com.example.hotel.dto.booking.request;

import jakarta.validation.Valid;
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
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 5000) String notes,
        @NotEmpty List<@Valid RoomRequest> rooms) {}
