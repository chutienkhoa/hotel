package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.RoomChangeReviewResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates Room Change after Check-in as one atomic business transaction. Room Change operates
 * on exactly one currently occupied room at a time, never mutates the immutable
 * {@link com.example.hotel.entity.booking.ReservationRoom} booking/pricing snapshot, and preserves
 * complete occupancy history through {@link StayRoomAssignment}.
 */
@Service
public class RoomChangeService {

    private final ReservationRepository reservations;
    private final StayRepository stays;
    private final StayRoomAssignmentRepository assignments;
    private final RoomRepository rooms;
    private final AuditLogRepository audits;
    private final ReservationMapper reservationMapper;
    private final RoomAvailabilityService roomAvailability;
    private final Clock clock;

    /**
     * Creates the Room Change service with its collaborators.
     *
     * @param reservations repository used to load and validate the owning Reservation
     * @param stays repository used to load the active Stay
     * @param assignments repository used to read and mutate actual room-occupancy history
     * @param rooms repository used to lock and validate Room state
     * @param audits repository used to write the existing-style CHANGE_ROOM audit entry
     * @param reservationMapper mapper used to build the Reservation response returned on success
     * @param roomAvailability shared lifecycle-aware booking-availability primitive used for the target room
     * @param clock authoritative hotel business clock
     */
    public RoomChangeService(
            ReservationRepository reservations,
            StayRepository stays,
            StayRoomAssignmentRepository assignments,
            RoomRepository rooms,
            AuditLogRepository audits,
            ReservationMapper reservationMapper,
            RoomAvailabilityService roomAvailability,
            Clock clock) {
        this.roomAvailability = roomAvailability;
        this.reservations = reservations;
        this.stays = stays;
        this.assignments = assignments;
        this.rooms = rooms;
        this.audits = audits;
        this.reservationMapper = reservationMapper;
        this.clock = clock;
    }

    /**
     * Lists active Rooms available for the remaining planned occupancy period of the specific
     * lineage being replaced, reusing the existing overlap semantics. Availability never relies on
     * current {@code Room.status} alone. This list is UI guidance only; {@link #changeRoom} always
     * re-locks and re-validates independently.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId the room currently occupied, being replaced
     * @return active Rooms available for the remainder of that lineage's planned stay
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> candidateRooms(UUID reservationId, UUID currentRoomId) {
        StayRoomAssignment openAssignment = openAssignmentOrThrow(reservationId, currentRoomId);
        LocalDate today = LocalDate.now(clock);
        // CURRENT planned departure (moves with Stay Extension); the original booking dates are not used here.
        LocalDate plannedCheckOutDate = openAssignment.getStay().getReservation().getCheckOutDate();
        // Aligned with the authoritative target-room check in changeRoom (active + AVAILABLE) so
        // MAINTENANCE and OUT_OF_ORDER rooms are excluded the same way instead of only one of them.
        List<Room> candidates = rooms.findByActiveTrue().stream()
                .filter(Room::isReadyForCheckIn)
                .filter(room -> !room.getId().equals(currentRoomId))
                .toList();
        Set<UUID> conflicted = roomAvailability.conflictedRoomIds(
                candidates.stream().map(Room::getId).toList(), today, plannedCheckOutDate);
        return candidates.stream()
                .filter(room -> !conflicted.contains(room.getId()))
                .map(room -> new RoomLookupResponse(
                        room.getId(), room.getRoomNumber(), room.getStatus().name(), room.isActive()))
                .toList();
    }

    /**
     * Builds the read-only Room Change Review without persisting anything.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId the room currently occupied, being replaced
     * @param request the submitted target room, reason, and notes
     * @return the Review data
     * @throws ResponseStatusException if the current room is not the Stay's open assignment, or
     *     the target room does not exist, or the target equals the current room
     */
    @Transactional(readOnly = true)
    public RoomChangeReviewResponse review(UUID reservationId, UUID currentRoomId, RoomChangeRequest request) {
        Reservation reservation = loadReservation(reservationId);
        StayRoomAssignment openAssignment = openAssignmentOrThrow(reservationId, currentRoomId);
        if (currentRoomId.equals(request.targetRoomId())) {
            throw bad("Replacement room must be different from the current room");
        }
        Room currentRoom = openAssignment.getRoom();
        Room targetRoom = roomOrThrow(request.targetRoomId());
        return new RoomChangeReviewResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                currentRoom.getId(),
                currentRoom.getRoomNumber(),
                targetRoom.getId(),
                targetRoom.getRoomNumber(),
                request.reason(),
                request.notes(),
                reservation.getCheckOutDate());
    }

    /**
     * Executes one atomic Room Change: closes the current room's open assignment, opens a
     * replacement assignment in the same lineage, marks the vacated old Room DIRTY (it needs housekeeping), occupies the
     * replacement Room, and writes the existing-style CHANGE_ROOM audit entry. Any failure rolls
     * back every effect since all of it runs inside this one transaction.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId the room currently occupied, being replaced
     * @param request the submitted target room, reason, and notes
     * @return the Reservation response, unchanged in status
     * @throws ResponseStatusException if any precondition, availability, or state check fails
     */
    @Transactional
    public Response changeRoom(UUID reservationId, UUID currentRoomId, RoomChangeRequest request) {
        // Lock order shared with Stay Extension and check-out: the Stay first, then the rooms (sorted by id below).
        // The Reservation is read after the Stay lock so its state is never older than the lock.
        Optional<Stay> lockedStay = stays.findByReservationIdForUpdate(reservationId);
        Reservation reservation = loadReservation(reservationId);
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw conflict("Room Change requires a CHECKED_IN Reservation");
        }
        Stay stay = lockedStay.orElseThrow(() -> conflict("Stay not found for reservation"));
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Room Change requires an active CHECKED_IN Stay");
        }
        StayRoomAssignment openAssignment = assignments
                .findOpenByStayIdAndRoomId(stay.getId(), currentRoomId)
                .orElseThrow(() -> conflict("Room is not currently assigned to this stay"));
        UUID targetRoomId = request.targetRoomId();
        if (targetRoomId.equals(currentRoomId)) {
            throw bad("Replacement room must be different from the current room");
        }
        if (request.reason() == null) {
            throw bad("Reason is required");
        }
        if (request.reason() == RoomChangeReason.OTHER
                && (request.notes() == null || request.notes().isBlank())) {
            throw bad("Notes are required when reason is OTHER");
        }
        LocalDate plannedCheckOutDate = reservation.getCheckOutDate();
        LocalDate today = LocalDate.now(clock);
        if (!today.isBefore(plannedCheckOutDate)) {
            throw conflict(
                    "Room Change is not allowed on or after the planned check-out date; "
                            + "use check-out or a stay extension instead");
        }

        List<UUID> roomIds = List.of(currentRoomId, targetRoomId).stream().sorted().toList();
        List<Room> lockedRooms = rooms.lockAllByIdIn(roomIds);
        if (lockedRooms.size() != roomIds.size()) {
            throw notFound("Room");
        }
        Map<UUID, Room> roomsById = lockedRooms.stream().collect(Collectors.toMap(Room::getId, room -> room));
        Room currentRoom = roomsById.get(currentRoomId);
        Room targetRoom = roomsById.get(targetRoomId);

        StayRoomAssignment revalidatedAssignment = assignments
                .findOpenByStayIdAndRoomId(stay.getId(), currentRoomId)
                .orElseThrow(() -> conflict("Room is not currently assigned to this stay"));

        if (!targetRoom.isActive() || targetRoom.getStatus() != RoomStatus.AVAILABLE) {
            throw conflict("Room is not available for room change");
        }
        // Lifecycle-aware inventory check, made while both room rows are locked.
        if (roomAvailability.hasInventoryConflict(targetRoomId, today, plannedCheckOutDate)) {
            throw conflict("Room is already booked for these dates");
        }

        CurrentUser user = currentUser();
        Instant changedAt = Instant.now(clock);

        revalidatedAssignment.close(changedAt);
        revalidatedAssignment.audit(user.id());
        // Force the close to flush before inserting the replacement: Hibernate's default flush
        // order applies all pending inserts before updates, regardless of code order, so without
        // this explicit flush the two open rows for this lineage would briefly coexist mid-flush
        // and violate ux_stay_room_assignment_open_lineage.
        assignments.flush();

        StayRoomAssignment replacement = new StayRoomAssignment(
                stay,
                targetRoom,
                revalidatedAssignment.getOriginalReservationRoom(),
                changedAt,
                request.reason(),
                request.notes());
        replacement.audit(user.id());
        assignments.save(replacement);

        try {
            currentRoom.releaseForRoomChange();
            targetRoom.occupy();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        currentRoom.audit(user.id());
        targetRoom.audit(user.id());

        audits.save(new AuditLog(
                user.id(),
                "CHANGE_ROOM",
                reservation.getId(),
                "Room " + currentRoom.getRoomNumber(),
                "Room " + targetRoom.getRoomNumber()));

        return reservationMapper.toResponse(reservation);
    }

    /** Loads the open assignment for the current room, or rejects with a 409/404 as appropriate. */
    private StayRoomAssignment openAssignmentOrThrow(UUID reservationId, UUID currentRoomId) {
        Reservation reservation = loadReservation(reservationId);
        Stay stay = stays.findByReservationId(reservationId).orElseThrow(() -> conflict("Stay not found for reservation"));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN || stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Room Change requires an active CHECKED_IN Stay");
        }
        return assignments
                .findOpenByStayIdAndRoomId(stay.getId(), currentRoomId)
                .orElseThrow(() -> conflict("Room is not currently assigned to this stay"));
    }

    private Reservation loadReservation(UUID id) {
        return reservations.findById(id).orElseThrow(() -> notFound("Reservation"));
    }

    private Room roomOrThrow(UUID id) {
        return rooms.findById(id).orElseThrow(() -> notFound("Room"));
    }

    /**
     * Lấy người dùng đã được xác thực từ security context.
     *
     * @return người dùng hiện tại
     * @throws ResponseStatusException nếu không có principal hợp lệ
     */
    private CurrentUser currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.username());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String resourceName) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
