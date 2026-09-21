package com.example.hotel.mapper.room;

import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import org.springframework.stereotype.Component;

/** Converts Room and RoomType entities into client-safe management responses. */
@Component
public class RoomMapper {

    /**
     * Maps one Room entity to its management response.
     *
     * @param room room entity to map
     * @return the room response
     */
    public RoomResponse toResponse(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getRoomNumber(),
                toResponse(room.getRoomType()),
                room.getFloor(),
                room.getStatus().name(),
                room.isActive());
    }

    /**
     * Maps one RoomType entity to the read-only fields needed by Room Management.
     *
     * @param roomType RoomType entity to map
     * @return the RoomType response
     */
    public RoomTypeResponse toResponse(RoomType roomType) {
        return new RoomTypeResponse(roomType.getId(), roomType.getCode(), roomType.getName());
    }
}
