package com.example.hotel.dto.booking.request;

import com.example.hotel.common.validation.RequiresOtaBookingReference;
import com.example.hotel.entity.booking.BookingSource;
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

/** Dữ liệu request tạo reservation nháp. */
@RequiresOtaBookingReference
public record CreateRequest(
        @NotNull UUID guestId,
        @NotNull LocalDate checkInDate,
        @NotNull LocalDate checkOutDate,
        @NotNull(message = "{validation.reservation.adultCount.required}")
                @Min(value = 1, message = "{validation.reservation.adultCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer adultCount,
        @NotNull(message = "{validation.reservation.childCount.required}")
                @Min(value = 0, message = "{validation.reservation.childCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer childCount,
        @NotNull BookingSource source,
        @Size(max = 255) String otaBookingReference,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 5000) String notes,
        @NotEmpty List<@Valid RoomRequest> rooms) {}
