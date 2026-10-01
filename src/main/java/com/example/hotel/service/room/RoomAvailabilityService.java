package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.repository.room.RoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single source of BOOKING availability. It answers which Rooms can be offered for a stay period, keeping two
 * concepts apart. BOOKING availability is about a requested hotel-night interval {@code [in, out)} (half-open: a stay
 * ending on a date does not block one starting on it) and is lifecycle-aware:
 * <ul>
 *   <li>CONFIRMED Reservations block through their immutable {@code ReservationRoom} intervals;</li>
 *   <li>CHECKED_IN Stays block through their ACTUAL {@code StayRoomAssignment}s, so after a Room Change the room the
 *       guest left is released and the room they occupy is protected (see {@code RoomRepository
 *       .findRoomIdsWithInventoryConflict}). The CHECKED_IN {@code ReservationRoom} is never counted.</li>
 * </ul>
 * A Room Change on hotel date D transfers inventory starting D; an open assignment protects at least the current
 * hotel night even past its planned check-out. CHECK-IN readiness is separate and about right now: the Room must
 * additionally be AVAILABLE, the only check-in-ready RoomStatus in V1. Current OCCUPIED, DIRTY or CLEANING status
 * never blocks a future booking. Existing conflicts already stored are never repaired here.
 */
@Service
public class RoomAvailabilityService {

    /** Sentinel meaning "exclude no Stay" (a real Stay identifier is never all zeros). */
    private static final UUID NO_STAY = new UUID(0L, 0L);

    /** Sentinel meaning "exclude no Reservation" (a real Reservation identifier is never all zeros). */
    private static final UUID NO_RESERVATION = new UUID(0L, 0L);

    private final RoomRepository roomRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param roomRepository source of active Rooms and of the lifecycle-aware overlap query
     * @param clock authoritative hotel business clock; its zone defines hotel dates
     */
    public RoomAvailabilityService(RoomRepository roomRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.clock = clock;
    }

    /**
     * Lists Rooms that can be BOOKED for {@code [checkInDate, checkOutDate)}: bookable inventory without an
     * inventory conflict. Current RoomStatus OCCUPIED, DIRTY or CLEANING does not exclude a Room.
     *
     * @param checkInDate inclusive requested check-in date
     * @param checkOutDate exclusive requested check-out date
     * @return matching Rooms ordered by room number
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> bookableRoomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomsForPeriod(checkInDate, checkOutDate, Room::isBookableInventory);
    }

    /**
     * Lists Rooms that can be checked in IMMEDIATELY for {@code [checkInDate, checkOutDate)}: active AVAILABLE
     * Rooms without an inventory conflict. DIRTY, CLEANING, MAINTENANCE, OUT_OF_ORDER and OCCUPIED Rooms are not
     * offered.
     *
     * @param checkInDate inclusive requested check-in date (the hotel current date for a Walk-in)
     * @param checkOutDate exclusive requested check-out date
     * @return matching Rooms ordered by room number
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> checkInReadyRoomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomsForPeriod(checkInDate, checkOutDate, Room::isReadyForCheckIn);
    }

    /**
     * Tells whether one Room can be checked in IMMEDIATELY for {@code [checkInDate, checkOutDate)}: it is active
     * AVAILABLE and has no inventory conflict. This is the single predicate behind
     * {@link #checkInReadyRoomsForPeriod} and pre-check-in Room reassignment.
     *
     * @param room the Room to test
     * @param checkInDate inclusive check-in date
     * @param checkOutDate exclusive check-out date
     * @return {@code true} when the Room is check-in-ready and conflict-free for the period
     */
    public boolean isCheckInReadyForPeriod(Room room, LocalDate checkInDate, LocalDate checkOutDate) {
        return room.isReadyForCheckIn() && !hasInventoryConflict(room.getId(), checkInDate, checkOutDate);
    }

    /**
     * The shared lifecycle-aware overlap primitive for one Room. Authoritative write operations (Confirm, Room Change)
     * call it while holding the Room row lock, so it always reads committed data at that point.
     *
     * @param roomId Room identifier
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @return {@code true} when the Room is already allocated for any part of {@code [in, out)}
     */
    public boolean hasInventoryConflict(UUID roomId, LocalDate in, LocalDate out) {
        return !conflictedRoomIds(List.of(roomId), in, out).isEmpty();
    }

    /**
     * The shared lifecycle-aware overlap primitive for many Rooms, in a single bounded query.
     *
     * @param roomIds Rooms to test
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @return the identifiers of the Rooms that are already allocated for any part of {@code [in, out)}
     */
    public Set<UUID> conflictedRoomIds(Collection<UUID> roomIds, LocalDate in, LocalDate out) {
        return conflictedRoomIds(roomIds, in, out, NO_STAY, NO_RESERVATION);
    }

    /**
     * The shared primitive with one Stay's own allocation ignored. Only Stay Extension passes a Stay: the extending
     * Stay's open assignment would otherwise conflict with itself (it protects at least the current night). Confirm,
     * Room Change and the lookups never exclude anything.
     *
     * @param roomIds Rooms to test
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @param excludedStayId the Stay whose own allocation is ignored
     * @return the identifiers of the Rooms allocated to anything else for any part of {@code [in, out)}
     */
    public Set<UUID> conflictedRoomIds(Collection<UUID> roomIds, LocalDate in, LocalDate out, UUID excludedStayId) {
        return conflictedRoomIds(roomIds, in, out, excludedStayId, NO_RESERVATION);
    }

    /**
     * Tests inventory while ignoring only the CONFIRMED ReservationRoom rows owned by one Reservation. This is used
     * solely by the controlled pre-check-in date-change operation so its current rows do not conflict with their own
     * proposed interval. Other Reservations and every active StayRoomAssignment remain visible.
     *
     * @param roomIds Rooms to test
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @param excludedReservationId Reservation whose own confirmed rows are ignored
     * @return the identifiers of Rooms allocated to anything else for any part of {@code [in, out)}
     */
    public Set<UUID> conflictedRoomIdsExcludingReservation(
            Collection<UUID> roomIds, LocalDate in, LocalDate out, UUID excludedReservationId) {
        return conflictedRoomIds(roomIds, in, out, NO_STAY, excludedReservationId);
    }

    /**
     * Executes the shared bounded overlap query with independently explicit Stay and Reservation exclusions.
     *
     * @param roomIds Rooms to test
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @param excludedStayId Stay whose assignments are ignored, or the no-Stay sentinel
     * @param excludedReservationId Reservation whose booked rows are ignored, or the no-Reservation sentinel
     * @return identifiers of Rooms that conflict
     */
    private Set<UUID> conflictedRoomIds(
            Collection<UUID> roomIds,
            LocalDate in,
            LocalDate out,
            UUID excludedStayId,
            UUID excludedReservationId) {
        if (roomIds.isEmpty()) {
            return Set.of();
        }
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        return new HashSet<>(roomRepository.findRoomIdsWithInventoryConflict(
                roomIds,
                in,
                out,
                out.atStartOfDay(zone).toInstant(),
                in.plusDays(1).atStartOfDay(zone).toInstant(),
                !today.isBefore(in),
                ReservationStatus.CONFIRMED,
                StayStatus.CHECKED_IN,
                excludedStayId,
                excludedReservationId));
    }

    private List<RoomLookupResponse> roomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate, Predicate<Room> eligible) {
        List<Room> candidates = roomRepository.findByActiveTrue().stream().filter(eligible).toList();
        Set<UUID> conflicted = conflictedRoomIds(candidates.stream().map(Room::getId).toList(), checkInDate, checkOutDate);
        return candidates.stream()
                .filter(room -> !conflicted.contains(room.getId()))
                .sorted(Comparator.comparing(Room::getRoomNumber))
                .map(room -> new RoomLookupResponse(room.getId(), room.getRoomNumber(), room.getStatus().name(), room.isActive()))
                .toList();
    }
}
