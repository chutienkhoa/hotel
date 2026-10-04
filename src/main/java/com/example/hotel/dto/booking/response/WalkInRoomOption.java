package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One selectable Room in the Walk-in Room Selection table. Every value is read from the Room and its Room Type;
 * the nightly rate is not part of it because the Walk-in form, not the Room, owns the rate.
 *
 * @param id the Room identifier submitted in the Walk-in request
 * @param roomNumber the Room's display number
 * @param roomTypeName the Room Type display name, or {@code null} when the Room has no Room Type
 * @param adultCapacity the Room Type's ADULT capacity, or {@code null} when it is not configured
 * @param status the current Room status (always AVAILABLE for an offered Room)
 */
public record WalkInRoomOption(
        UUID id, String roomNumber, String roomTypeName, Integer adultCapacity, String status) {}
