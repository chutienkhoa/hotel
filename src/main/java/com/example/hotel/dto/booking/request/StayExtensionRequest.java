package com.example.hotel.dto.booking.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The only inputs of a Stay Extension: the check-out date the caller saw (stale-request protection) and the requested
 * new planned check-out date. Rooms, rates, amounts and currency are never accepted from the client.
 *
 * @param expectedCurrentCheckOutDate planned check-out the caller based the request on
 * @param newCheckOutDate requested later planned check-out
 */
public record StayExtensionRequest(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedCurrentCheckOutDate,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newCheckOutDate) {}
