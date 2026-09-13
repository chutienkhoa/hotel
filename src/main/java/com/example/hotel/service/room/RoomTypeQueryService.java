package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.mapper.room.RoomMapper;
import com.example.hotel.repository.room.RoomTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provides read-only RoomType data for Room Management selection and display. */
@Service
public class RoomTypeQueryService {

    private final RoomTypeRepository roomTypeRepository;
    private final RoomMapper roomMapper;

    /**
     * Creates the query service with RoomType persistence and mapping collaborators.
     *
     * @param roomTypeRepository repository used to load RoomTypes
     * @param roomMapper mapper used to create client-safe responses
     */
    public RoomTypeQueryService(RoomTypeRepository roomTypeRepository, RoomMapper roomMapper) {
        this.roomTypeRepository = roomTypeRepository;
        this.roomMapper = roomMapper;
    }

    /**
     * Retrieves the RoomTypes that may be selected while creating or updating a room.
     *
     * @return the read-only RoomType list
     */
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> findAll() {
        return roomTypeRepository.findAll().stream().map(roomMapper::toResponse).toList();
    }
}
