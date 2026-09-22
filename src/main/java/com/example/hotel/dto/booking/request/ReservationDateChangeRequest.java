package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Carries only the two dates accepted by the dedicated confirmed-reservation date-change operation.
 *
 * @param newCheckInDate replacement planned check-in date
 * @param newCheckOutDate replacement planned check-out date
 */
public record ReservationDateChangeRequest(
        @NotNull(message = "{validation.reservation.changeDates.checkIn.required}")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                LocalDate newCheckInDate,
        @NotNull(message = "{validation.reservation.changeDates.checkOut.required}")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                LocalDate newCheckOutDate) {}
