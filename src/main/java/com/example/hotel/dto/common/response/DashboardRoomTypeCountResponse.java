package com.example.hotel.dto.common.response;

/** Represents assigned ReservationRoom count for one current RoomType in Dashboard Analytics v2. */
public record DashboardRoomTypeCountResponse(String roomTypeCode, String roomTypeName, long count) {}
