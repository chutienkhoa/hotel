package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.RoomReassignmentCandidateResponse;
import com.example.hotel.dto.booking.response.RoomReassignmentFormResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.exception.RoomReassignmentException.Reason;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pre-check-in Room reassignment: replaces the Room of ONE assigned line of a CONFIRMED Reservation that has not
 * been checked in, so an arrival blocked by an unusable Room can be recovered without cancelling the Reservation.
 * This is deliberately separate from the in-stay Room Change ({@link RoomChangeService}): no guest has occupied the
 * old Room, so its status is never touched, no Stay or StayRoomAssignment is involved, and the booked nightly-rate
 * snapshot is never repriced. The operation is audited as {@code REASSIGN_ROOM}, never as {@code CHANGE_ROOM}.
 */
@Service
public class ReservationRoomReassignmentService {

    /** Audit action recorded for this operation; distinct from the in-stay {@code CHANGE_ROOM}. */
    public static final String AUDIT_ACTION = "REASSIGN_ROOM";

    private final ReservationRepository reservations;
    private final StayRepository stays;
    private final RoomRepository rooms;
    private final RoomAvailabilityService roomAvailability;
    private final AuditLogRepository audits;

    /**
     * Creates the service.
     *
     * @param reservations repository used to lock and load the Reservation
     * @param stays repository used to detect an existing Stay
     * @param rooms repository used to lock and list Rooms
     * @param roomAvailability shared check-in-ready and conflict predicate
     * @param audits repository used to record the {@code REASSIGN_ROOM} audit entry
     */
    public ReservationRoomReassignmentService(
            ReservationRepository reservations,
            StayRepository stays,
            RoomRepository rooms,
            RoomAvailabilityService roomAvailability,
            AuditLogRepository audits) {
        this.reservations = reservations;
        this.stays = stays;
        this.rooms = rooms;
        this.roomAvailability = roomAvailability;
        this.audits = audits;
    }

    /**
     * Builds the reassignment form: the line being replaced and every active AVAILABLE Room that has no overlapping
     * CONFIRMED or CHECKED_IN Reservation for the line's booked dates and is not already on this Reservation. The
     * list is guidance; {@link #reassign} re-validates under locks with the very same predicate.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId Room of the line being replaced
     * @return the form data
     * @throws ResponseStatusException if the Reservation does not exist
     * @throws RoomReassignmentException if the Reservation can no longer be reassigned or the line is gone
     */
    @Transactional(readOnly = true)
    public RoomReassignmentFormResponse form(UUID reservationId, UUID currentRoomId) {
        Reservation reservation = reservations.findById(reservationId).orElseThrow(() -> notFound("Reservation"));
        ReservationRoom line = requireReassignable(reservation, currentRoomId);
        List<UUID> assigned = reservation.getRooms().stream().map(other -> other.getRoom().getId()).toList();
        List<RoomReassignmentCandidateResponse> candidates = rooms.findActiveWithRoomType().stream()
                .filter(room -> !assigned.contains(room.getId()))
                .filter(room -> roomAvailability.isCheckInReadyForPeriod(
                        room, line.getCheckInDate(), line.getCheckOutDate()))
                .filter(room -> capacityAllowsReplacement(reservation, currentRoomId, room))
                .sorted(Comparator.comparing(Room::getRoomNumber))
                .map(room -> new RoomReassignmentCandidateResponse(
                        room.getId(),
                        room.getRoomNumber(),
                        room.getRoomType() == null ? null : room.getRoomType().getName()))
                .toList();
        Room current = line.getRoom();
        return new RoomReassignmentFormResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                current.getId(),
                current.getRoomNumber(),
                current.getRoomType() == null ? null : current.getRoomType().getName(),
                line.getCheckInDate(),
                line.getCheckOutDate(),
                line.getNightlyRate(),
                reservation.getCurrency(),
                candidates);
    }

    /**
     * Replaces one assigned Room atomically. Locks the two Rooms in id order and then the Reservation row (the same
     * Rooms-then-Reservation order as check-in, which also locks the Reservation's Rooms), so it serializes with
     * check-in, other reassignments of the same line and any other booking of the replacement Room. Under the
     * locks it re-validates that the Reservation is still CONFIRMED with no Stay, that the line still holds the
     * expected Room, and that the replacement is active AVAILABLE and conflict-free. Nothing else is changed: not the
     * old Room's status, the booked rate, the total, the dates or any other line.
     *
     * @param reservationId Reservation identifier
     * @param currentRoomId Room of the line being replaced (the stale-UI guard)
     * @param targetRoomId replacement Room
     * @throws ResponseStatusException if the Reservation or a Room does not exist
     * @throws RoomReassignmentException if the state changed or the replacement is not eligible
     */
    @Transactional
    public void reassign(UUID reservationId, UUID currentRoomId, UUID targetRoomId) {
        CurrentUser user = currentUser();
        if (currentRoomId.equals(targetRoomId)) {
            throw new RoomReassignmentException(Reason.ROOM_ALREADY_ASSIGNED, "Replacement must differ from the current room");
        }
        List<Room> locked = rooms.lockAllByIdIn(List.of(currentRoomId, targetRoomId).stream().sorted().toList());
        Room target = locked.stream().filter(room -> room.getId().equals(targetRoomId)).findFirst()
                .orElseThrow(() -> notFound("Room"));
        Reservation reservation = reservations.findByIdForUpdate(reservationId).orElseThrow(() -> notFound("Reservation"));
        ReservationRoom line = requireReassignable(reservation, currentRoomId);
        if (reservation.findRoomLine(targetRoomId) != null) {
            throw new RoomReassignmentException(Reason.ROOM_ALREADY_ASSIGNED, "Room is already assigned to this reservation");
        }
        if (!roomAvailability.isCheckInReadyForPeriod(target, line.getCheckInDate(), line.getCheckOutDate())) {
            throw new RoomReassignmentException(Reason.ROOM_UNAVAILABLE, "Room is no longer available");
        }
        requireCapacityOfResultingRooms(reservation, currentRoomId, target);
        Room previous = reservation.reassignRoom(currentRoomId, target);
        line.audit(user.id());
        reservation.audit(user.id());
        audits.save(new AuditLog(
                user.id(),
                AUDIT_ACTION,
                reservation.getId(),
                "Room " + previous.getRoomNumber(),
                "Room " + target.getRoomNumber()));
    }

    private boolean capacityAllowsReplacement(Reservation reservation, UUID currentRoomId, Room candidate) {
        List<Room> finalRooms = reservation.getRooms().stream()
                .map(line -> line.getRoom().getId().equals(currentRoomId) ? candidate : line.getRoom())
                .toList();
        return AdultCapacityRules.evaluate(reservation.getAdultCount(), finalRooms).valid();
    }

    /**
     * Validates the FINAL room set (current rooms with the replaced one swapped for the target) against the shared
     * adult-capacity rule, before anything is changed.
     */
    private void requireCapacityOfResultingRooms(Reservation reservation, UUID currentRoomId, Room target) {
        List<Room> finalRooms = reservation.getRooms().stream()
                .map(line -> line.getRoom().getId().equals(currentRoomId) ? target : line.getRoom())
                .toList();
        AdultCapacityRules.Result capacity = AdultCapacityRules.evaluate(reservation.getAdultCount(), finalRooms);
        switch (capacity.outcome()) {
            case INSUFFICIENT_ADULT_CAPACITY -> throw new RoomReassignmentException(
                    Reason.INSUFFICIENT_ADULT_CAPACITY, "The resulting rooms cannot host the adults");
            case CAPACITY_NOT_CONFIGURED -> throw new RoomReassignmentException(
                    Reason.CAPACITY_NOT_CONFIGURED, "Room capacity is not configured");
            case VALID -> { }
        }
    }

    private ReservationRoom requireReassignable(Reservation reservation, UUID currentRoomId) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new RoomReassignmentException(Reason.RESERVATION_STATE_CHANGED, "Reservation is not CONFIRMED");
        }
        if (stays.existsByReservationId(reservation.getId())) {
            throw new RoomReassignmentException(Reason.STAY_ALREADY_EXISTS, "A stay already exists");
        }
        ReservationRoom line = reservation.findRoomLine(currentRoomId);
        if (line == null) {
            throw new RoomReassignmentException(Reason.ASSIGNMENT_CHANGED, "Assigned room is no longer on the reservation");
        }
        return line;
    }

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

    private ResponseStatusException notFound(String resourceName) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
    }
}
