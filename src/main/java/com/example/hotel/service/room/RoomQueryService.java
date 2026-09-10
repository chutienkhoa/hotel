package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.repository.room.RoomRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides read-only room lookup data for reservation creation.
 */
@Service
public class RoomQueryService {

    private final RoomRepository roomRepository;

    /**
     * Creates the query service with the repository used to load rooms.
     *
     * @param roomRepository repository used to load rooms
     */
    public RoomQueryService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /**
     * Retrieves the room fields required by the reservation form without changing room state.
     *
     * @return the room lookup entries
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> findAllForReservationCreation() {
        return roomRepository.findAll().stream()
                .map(room -> new RoomLookupResponse(
                        room.getId(),
                        room.getRoomNumber(),
                        room.getStatus().name(),
                        room.isActive()))
                .toList();
    }
}
