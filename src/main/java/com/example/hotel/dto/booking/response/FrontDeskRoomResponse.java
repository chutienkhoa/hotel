package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One Room shown inside a Front Desk row.
 *
 * @param roomId Room identifier
 * @param roomNumber Room number
 * @param roomTypeName RoomType name
 * @param issue the Arrival Readiness blocker of this Room for an arrival, otherwise {@code null}
 */
public record FrontDeskRoomResponse(UUID roomId, String roomNumber, String roomTypeName, ArrivalIssueCode issue) {}
