package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * Supplies one eligible replacement Room for the Room Change form. Carries read-only display data only;
 * eligibility is decided by {@link com.example.hotel.service.booking.RoomChangeService#candidateRooms}.
 *
 * @param id the replacement room identifier submitted as the Room Change target
 * @param roomNumber the replacement room's display number
 * @param roomTypeName the replacement room's Room Type name
 * @param capacity the replacement room's Room Type capacity, when defined
 * @param hasPrimaryImage whether the replacement room has a primary image to show
 */
public record RoomChangeCandidateResponse(
        UUID id, String roomNumber, String roomTypeName, Integer capacity, boolean hasPrimaryImage) {}
