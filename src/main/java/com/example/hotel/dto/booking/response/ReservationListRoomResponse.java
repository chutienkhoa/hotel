package com.example.hotel.dto.booking.response;

/**
 * One room shown in the Reservation List Room(s) column.
 *
 * @param roomNumber the room number
 * @param roomTypeName the room type's display name
 */
public record ReservationListRoomResponse(String roomNumber, String roomTypeName) {
}
