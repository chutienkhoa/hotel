package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.RoomHistoryLineResponse;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides read-only presentation data for a Stay's actual physical room occupancy: the current
 * rooms (open {@link StayRoomAssignment} rows) for Reservation Detail, and the complete chronological
 * Room History for a CHECKED_IN or CHECKED_OUT Reservation.
 */
@Service
public class StayRoomAssignmentQueryService {

    private final StayRepository stays;
    private final StayRoomAssignmentRepository assignments;
    private final AppUserRepository appUsers;

    /**
     * Creates the query service with its persistence dependencies.
     *
     * @param stays repository used to resolve the Stay owning a Reservation
     * @param assignments repository used to read room-occupancy history
     * @param appUsers repository used to resolve the display username for each assignment's creator
     */
    public StayRoomAssignmentQueryService(
            StayRepository stays, StayRoomAssignmentRepository assignments, AppUserRepository appUsers) {
        this.stays = stays;
        this.assignments = assignments;
        this.appUsers = appUsers;
    }

    /**
     * Lists the rooms a Reservation's Stay currently occupies, or an empty list before Check-in.
     *
     * @param reservationId Reservation identifier
     * @return the current, open room assignments
     */
    @Transactional(readOnly = true)
    public List<CurrentRoomResponse> findCurrentRooms(UUID reservationId) {
        return stays.findByReservationId(reservationId)
                .map(Stay::getId)
                .map(assignments::findOpenByStayId)
                .orElseGet(List::of)
                .stream()
                .map(assignment -> new CurrentRoomResponse(
                        assignment.getId(),
                        assignment.getRoom().getId(),
                        assignment.getRoom().getRoomNumber(),
                        assignment.getAssignedFrom()))
                .toList();
    }

    /**
     * Lists the complete chronological Room History for a Reservation's Stay, grouped by lineage
     * (originally booked room) and ordered by time within each lineage, or an empty list before
     * Check-in.
     *
     * @param reservationId Reservation identifier
     * @return the Room History lines
     */
    @Transactional(readOnly = true)
    public List<RoomHistoryLineResponse> findHistory(UUID reservationId) {
        List<StayRoomAssignment> rows = stays.findByReservationId(reservationId)
                .map(Stay::getId)
                .map(assignments::findByStayIdOrderByLineageAndTime)
                .orElseGet(List::of);
        Map<UUID, String> usernamesById = resolveUsernames(rows);
        return rows.stream()
                .map(assignment -> new RoomHistoryLineResponse(
                        assignment.getRoom().getRoomNumber(),
                        assignment.getAssignedFrom(),
                        assignment.getAssignedTo(),
                        assignment.getReason() == null ? "Initial Check-in" : assignment.getReason().getDisplayName(),
                        usernamesById.getOrDefault(assignment.getCreatedBy(), "—"),
                        assignment.getRoom().getId()))
                .toList();
    }

    /** Batch-resolves each assignment's creator username, avoiding an N+1 lookup per row. */
    private Map<UUID, String> resolveUsernames(List<StayRoomAssignment> rows) {
        Set<UUID> userIds = rows.stream().map(StayRoomAssignment::getCreatedBy).collect(Collectors.toSet());
        return appUsers.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));
    }
}
