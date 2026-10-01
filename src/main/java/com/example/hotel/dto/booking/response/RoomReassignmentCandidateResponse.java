package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One replacement Room offered for a pre-check-in reassignment.
 *
 * @param id Room identifier
 * @param roomNumber Room number
 * @param roomTypeName RoomType name, when available
 */
public record RoomReassignmentCandidateResponse(UUID id, String roomNumber, String roomTypeName) {}
