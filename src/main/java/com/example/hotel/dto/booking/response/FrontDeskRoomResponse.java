package com.example.hotel.dto.booking.response;

import java.util.UUID;

/**
 * One Room shown inside a Front Desk row.
 *
 * @param roomId Room identifier
 * @param roomNumber Room number
 * @param roomTypeId RoomType identifier, used by the In-house Room Type filter; {@code null} when not loaded
 * @param roomTypeName RoomType name
 * @param issue the Arrival Readiness blocker of this Room for an arrival, otherwise {@code null}
 */
public record FrontDeskRoomResponse(UUID roomId, String roomNumber, UUID roomTypeId, String roomTypeName, ArrivalIssueCode issue) {

    /**
     * Creates a Room without a RoomType identifier (for display-only callers).
     *
     * @param roomId Room identifier
     * @param roomNumber Room number
     * @param roomTypeName RoomType name
     * @param issue the Arrival Readiness blocker, or {@code null}
     */
    public FrontDeskRoomResponse(UUID roomId, String roomNumber, String roomTypeName, ArrivalIssueCode issue) {
        this(roomId, roomNumber, null, roomTypeName, issue);
    }
}
