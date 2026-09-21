package com.example.hotel.repository.booking;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The earliest upcoming arrival date of one Room, read in a single grouped query for all Rooms.
 *
 * @param roomId Room identifier
 * @param nextArrivalDate earliest booked check-in date of a qualifying Reservation
 */
public record RoomNextArrivalRow(UUID roomId, LocalDate nextArrivalDate) {}
