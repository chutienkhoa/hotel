package com.example.hotel.repository.booking;

import java.util.UUID;

/**
 * One distinct RoomType booked by a Reservation, read in a single batched query for all Reservations of a period.
 *
 * @param reservationId Reservation identifier
 * @param roomTypeCode stable RoomType code
 * @param roomTypeName RoomType name as stored
 */
public record ReservationRoomTypeRow(UUID reservationId, String roomTypeCode, String roomTypeName) {}
