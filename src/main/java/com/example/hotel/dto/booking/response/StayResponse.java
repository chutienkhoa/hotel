package com.example.hotel.dto.booking.response;

import java.time.Instant;
import java.util.UUID;

/** Exposes the Stay fields required by the reservation-scoped Folio page. */
public record StayResponse(
        UUID id, String status, Instant actualCheckInAt, Instant actualCheckOutAt) {}
