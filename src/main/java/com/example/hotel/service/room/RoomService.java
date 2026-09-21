package com.example.hotel.service.room;

import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.dto.room.request.RoomUpdateRequest;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.room.RoomMapper;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.repository.room.RoomTypeRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Provides authorized Room Management operations while preserving server-controlled fields. */
@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomMapper roomMapper;
    private final RoomInventoryHistoryService inventoryHistory;
    private final Clock clock;

    /**
     * Creates the service with the persistence and mapping collaborators used by Room Management.
     *
     * @param roomRepository repository used to manage rooms
     * @param roomTypeRepository repository used to resolve required RoomTypes
     * @param roomMapper mapper used to create client-safe responses
     * @param inventoryHistory service keeping RoomType and sellability history consistent with the Room
     * @param clock authoritative hotel business clock used for inventory history Instants
     */
    public RoomService(
            RoomRepository roomRepository,
            RoomTypeRepository roomTypeRepository,
            RoomMapper roomMapper,
            RoomInventoryHistoryService inventoryHistory,
            Clock clock) {
        this.roomRepository = roomRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.roomMapper = roomMapper;
        this.inventoryHistory = inventoryHistory;
        this.clock = clock;
    }

    /**
     * Retrieves all managed rooms.
     *
     * @return the room list
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> findAll() {
        return roomRepository.findAll().stream().map(roomMapper::toResponse).toList();
    }

    /**
     * Retrieves one room by its internal identifier.
     *
     * @param id room identifier
     * @return the room response
     * @throws ResponseStatusException if the room does not exist
     */
    @Transactional(readOnly = true)
    public RoomResponse findById(UUID id) {
        return roomMapper.toResponse(findRoom(id));
    }

    /**
     * Creates an active room with a backend-generated UUID and AVAILABLE operational status.
     *
     * @param request client-supplied mutable room-profile data
     * @return the persisted room response
     */
    @Transactional
    public RoomResponse create(RoomCreateRequest request) {
        Room room = Room.create(
                UUID.randomUUID(),
                request.roomNumber(),
                findRoomType(request.roomTypeId()),
                request.floor());
        UUID userId = currentUser().id();
        room.audit(userId);
        Room saved = roomRepository.save(room);
        inventoryHistory.initialize(saved, Instant.now(clock), userId);
        return roomMapper.toResponse(saved);
    }

    /**
     * Updates only the approved mutable profile fields of an existing room under the Room write lock.
     * A RoomType change also closes the open inventory period and opens a new one in the same transaction.
     *
     * @param id room identifier
     * @param request client-supplied mutable room-profile data
     * @return the updated room response
     * @throws ResponseStatusException if the room or selected RoomType does not exist
     */
    @Transactional
    public RoomResponse update(UUID id, RoomUpdateRequest request) {
        Room room = lockRoom(id);
        room.updateProfile(request.roomNumber(), findRoomType(request.roomTypeId()), request.floor());
        UUID userId = currentUser().id();
        room.audit(userId);
        Room saved = roomRepository.save(room);
        inventoryHistory.sync(saved, Instant.now(clock), userId);
        return roomMapper.toResponse(saved);
    }

    /**
     * Starts cleaning a dirty room.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse startCleaning(UUID id) {
        return transition(id, Room::startCleaning);
    }

    /**
     * Finishes cleaning a cleaning room.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse finishCleaning(UUID id) {
        return transition(id, Room::finishCleaning);
    }

    /**
     * Starts maintenance for an available room.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse startMaintenance(UUID id) {
        return transition(id, Room::startMaintenance);
    }

    /**
     * Finishes maintenance for a room in maintenance.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse finishMaintenance(UUID id) {
        return transition(id, Room::finishMaintenance);
    }

    /**
     * Marks an available room out of order.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse markOutOfOrder(UUID id) {
        return transition(id, Room::markOutOfOrder);
    }

    /**
     * Restores an out-of-order room to available service.
     *
     * @param id room identifier
     * @return the transitioned room response
     */
    @Transactional
    public RoomResponse restoreToService(UUID id) {
        return transition(id, Room::restoreToService);
    }

    /**
     * Applies one explicit Room operation while holding the same pessimistic Room lock used by check-in,
     * then aligns inventory history so only sellability-changing operations create a period.
     *
     * @param id room identifier
     * @param operation exactly one approved Room domain operation
     * @return the transitioned room response
     * @throws ResponseStatusException if the Room does not exist or its current status does not allow the operation
     */
    private RoomResponse transition(UUID id, Consumer<Room> operation) {
        Room room = lockRoom(id);
        try {
            operation.accept(room);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
        UUID userId = currentUser().id();
        room.audit(userId);
        Room saved = roomRepository.save(room);
        inventoryHistory.sync(saved, Instant.now(clock), userId);
        return roomMapper.toResponse(saved);
    }

    /**
     * Loads one room or returns the standard not-found response.
     *
     * @param id room identifier
     * @return the existing room
     * @throws ResponseStatusException if the room does not exist
     */
    private Room findRoom(UUID id) {
        return roomRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }

    /**
     * Loads and pessimistically locks one Room to coordinate status transitions with the existing check-in flow.
     *
     * @param id room identifier
     * @return the locked Room
     * @throws ResponseStatusException if the Room does not exist
     */
    private Room lockRoom(UUID id) {
        return roomRepository.lockAllByIdIn(List.of(id)).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }

    /**
     * Loads one required RoomType or returns the standard not-found response.
     *
     * @param id RoomType identifier
     * @return the existing RoomType
     * @throws ResponseStatusException if the RoomType does not exist
     */
    private RoomType findRoomType(UUID id) {
        return roomTypeRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room type not found"));
    }

    /**
     * Resolves the current JWT or session principal to the audit identity used by persisted entities.
     *
     * @return the authenticated application user
     * @throws ResponseStatusException if the active principal is not an application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }
}
