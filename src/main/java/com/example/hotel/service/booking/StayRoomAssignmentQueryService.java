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
     * Returns the nightly rate of each CURRENT room, keyed by Room identifier. The rate is the price snapshot of the
     * originally booked ReservationRoom line the assignment descends from (a Room Change keeps it), so no rate is
     * recalculated here.
     *
     * @param reservationId Reservation identifier
     * @return current Room identifier to nightly rate; empty before Check-in
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, java.math.BigDecimal> findCurrentRoomRates(UUID reservationId) {
        java.util.Map<UUID, java.math.BigDecimal> rates = new java.util.LinkedHashMap<>();
        stays.findByReservationId(reservationId)
                .map(Stay::getId)
                .map(assignments::findOpenByStayId)
                .orElseGet(List::of)
                .forEach(assignment -> rates.put(
                        assignment.getRoom().getId(), assignment.getOriginalReservationRoom().getNightlyRate()));
        return rates;
    }

    /**
     * Lists the rooms a CHECKED_OUT Stay released at checkout: the assignments closed at the latest close instant, so
     * rooms vacated earlier by a Room Change are not included. Empty before Check-in or while the Stay is still open.
     *
     * @param reservationId Reservation identifier
     * @return the assignments closed by the checkout
     */
    @Transactional(readOnly = true)
    public List<CurrentRoomResponse> findFinalRooms(UUID reservationId) {
        return finalAssignments(reservationId).stream()
                .map(assignment -> new CurrentRoomResponse(
                        assignment.getId(),
                        assignment.getRoom().getId(),
                        assignment.getRoom().getRoomNumber(),
                        assignment.getAssignedFrom()))
                .toList();
    }

    /**
     * Returns the nightly rate of each room released at checkout, keyed by Room identifier, from the booked price
     * snapshot the assignment descends from.
     *
     * @param reservationId Reservation identifier
     * @return final Room identifier to nightly rate; empty while the Stay is open
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, java.math.BigDecimal> findFinalRoomRates(UUID reservationId) {
        java.util.Map<UUID, java.math.BigDecimal> rates = new java.util.LinkedHashMap<>();
        finalAssignments(reservationId).forEach(assignment -> rates.put(
                assignment.getRoom().getId(), assignment.getOriginalReservationRoom().getNightlyRate()));
        return rates;
    }

    private List<StayRoomAssignment> finalAssignments(UUID reservationId) {
        List<StayRoomAssignment> rows = stays.findByReservationId(reservationId)
                .map(Stay::getId)
                .map(assignments::findByStayIdOrderByLineageAndTime)
                .orElseGet(List::of);
        java.util.Optional<java.time.Instant> closedAt = rows.stream()
                .map(StayRoomAssignment::getAssignedTo)
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder());
        if (closedAt.isEmpty() || rows.stream().anyMatch(row -> row.getAssignedTo() == null)) {
            return List.of();
        }
        return rows.stream().filter(row -> closedAt.get().equals(row.getAssignedTo())).toList();
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
