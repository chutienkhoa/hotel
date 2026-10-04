package com.example.hotel.service.room;

import com.example.hotel.common.TableSorts;
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
     * Retrieves the Rooms a reservation form may offer: bookable inventory, independent of the Room's current
     * operational status. Overlap with existing Reservations is validated per requested period (see
     * {@link RoomAvailabilityService}) and enforced when the Reservation is confirmed.
     *
     * @return the room lookup entries ordered by room number
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> findAllForReservationCreation() {
        return bookableRooms().map(this::toLookup).toList();
    }

    /** Retrieves bookable Rooms plus the assignments retained by a draft edit form. */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> findAllForReservationEditing(Collection<UUID> assignedRoomIds) {
        var roomsById = new LinkedHashMap<UUID, Room>();
        bookableRooms().forEach(room -> roomsById.put(room.getId(), room));
        roomRepository.findAllById(assignedRoomIds).forEach(room -> roomsById.put(room.getId(), room));
        return roomsById.values().stream().map(this::toLookup).toList();
    }

    private java.util.stream.Stream<Room> bookableRooms() {
        return roomRepository.findByActiveTrue().stream()
                .filter(Room::isBookableInventory)
                .sorted(java.util.Comparator.comparing(Room::getRoomNumber));
    }

    private RoomLookupResponse toLookup(Room room) {
        return new RoomLookupResponse(
                room.getId(),
                room.getRoomNumber(),
                room.getStatus().name(),
                room.isActive(),
                room.getRoomType() == null ? null : room.getRoomType().getName(),
                room.getRoomType() == null ? null : room.getRoomType().getCapacity());
    }

    /**
     * Retrieves the client-safe Room profile (Room Type and current operational status included) for a known set
     * of Room identifiers, used to enrich an already-resolved current-room list without reimplementing Room
     * Management's own data. Unknown identifiers are ignored.
     *
     * @param roomIds Room identifiers, possibly empty
     * @return the matching Room profiles, in no particular order
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> findAllByIds(Collection<UUID> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return List.of();
        }
        return roomRepository.findAllById(roomIds).stream().map(roomMapper::toResponse).toList();
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
                TableSorts.ROOM.resolve(criteria.getSort(), criteria.getDir()));
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
