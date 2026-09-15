package com.example.hotel.service.room;

import com.example.hotel.dto.room.request.RoomSearchCriteria;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.room.RoomMapper;
import com.example.hotel.repository.room.RoomRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides read-only Room lookup and database-backed list data for MVC presentation.
 */
@Service
public class RoomQueryService {

    private static final int ROOM_PAGE_SIZE = 10;

    private final RoomRepository roomRepository;
    private final RoomMapper roomMapper;

    /**
     * Creates the query service with the repository and mapper used to load rooms.
     *
     * @param roomRepository repository used to load rooms
     * @param roomMapper mapper used to prepare client-safe Room responses
     */
    public RoomQueryService(RoomRepository roomRepository, RoomMapper roomMapper) {
        this.roomRepository = roomRepository;
        this.roomMapper = roomMapper;
    }

    /**
     * Retrieves the room fields required by the reservation form without changing room state.
     *
     * @return the room lookup entries
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> findAllForReservationCreation() {
        return roomRepository.findByActiveTrueAndStatus(RoomStatus.AVAILABLE).stream()
                .map(room -> new RoomLookupResponse(
                        room.getId(),
                        room.getRoomNumber(),
                        room.getStatus().name(),
                        room.isActive()))
                .toList();
    }

    /** Retrieves active AVAILABLE Rooms plus the assignments retained by a draft edit form. */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> findAllForReservationEditing(Collection<UUID> assignedRoomIds) {
        var roomsById = new LinkedHashMap<UUID, Room>();
        roomRepository.findByActiveTrueAndStatus(RoomStatus.AVAILABLE)
                .forEach(room -> roomsById.put(room.getId(), room));
        roomRepository.findAllById(assignedRoomIds).forEach(room -> roomsById.put(room.getId(), room));
        return roomsById.values().stream()
                .map(room -> new RoomLookupResponse(room.getId(), room.getRoomNumber(), room.getStatus().name(), room.isActive()))
                .toList();
    }

    /**
     * Retrieves one database-backed page of Rooms matching every supplied optional filter.
     *
     * @param criteria normalized optional Room list filters
     * @param page zero-based requested page number
     * @return a page of client-safe Room responses
     */
    @Transactional(readOnly = true)
    public Page<RoomResponse> findPage(RoomSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                ROOM_PAGE_SIZE,
                Sort.by(Sort.Order.asc("roomNumber")));
        return roomRepository.findAll(specificationFor(criteria), pageable).map(roomMapper::toResponse);
    }

    /**
     * Builds the database predicate combining every supplied Room filter.
     *
     * <p>Each populated filter field contributes its own predicate on its own database column
     * or association; populated filters are combined with AND semantics, so a Room must match
     * every supplied filter to appear in the result.</p>
     *
     * @param criteria normalized optional Room list filters
     * @return the database specification for matching Room list rows
     */
    Specification<Room> specificationFor(RoomSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            if (criteria.getRoomNumber() != null) {
                String pattern = "%" + criteria.getRoomNumber().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("roomNumber")), pattern));
            }
            if (criteria.getRoomTypeId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("roomType").get("id"), criteria.getRoomTypeId()));
            }
            if (criteria.getFloor() != null) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("floor")), criteria.getFloor().toLowerCase(Locale.ROOT)));
            }
            if (criteria.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), criteria.getStatus()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
