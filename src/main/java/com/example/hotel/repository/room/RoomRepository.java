package com.example.hotel.repository.room;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ và khóa đồng thời cho phòng. */
public interface RoomRepository extends JpaRepository<Room, UUID>, JpaSpecificationExecutor<Room> {
    List<Room> findByActiveTrueAndStatus(RoomStatus status);

    /**
     * Retrieves every active Room regardless of current operational status, for date-range-aware
     * availability queries that must not rely solely on the current {@code Room.status}.
     *
     * @return every active Room
     */
    List<Room> findByActiveTrue();

    /**
     * Counts active Rooms grouped by their current operational status.
     *
     * @return status and count rows for active Rooms in stable status order
     */
    @Query(
            "SELECT r.status, COUNT(r) "
                    + "FROM Room r "
                    + "WHERE r.active = TRUE "
                    + "GROUP BY r.status "
                    + "ORDER BY r.status")
    List<Object[]> countActiveByStatus();

    /**
     * Loads every active Room with its RoomType in one query, in room-number order, for the housekeeping worklist.
     *
     * @return active Rooms with their RoomType initialized
     */
    @Query(
            "SELECT r FROM Room r JOIN FETCH r.roomType "
                    + "WHERE r.active = TRUE "
                    + "ORDER BY r.roomNumber")
    List<Room> findActiveWithRoomType();

    /**
     * Finds which of the given Rooms are NOT available for booking in the hotel-night interval
     * {@code [in, out)}, in ONE query. It is the shared, lifecycle-aware overlap primitive (used through
     * {@code RoomAvailabilityService}); callers never combine its two sources themselves:
     * <ul>
     *   <li>CONFIRMED Reservations block through their {@code ReservationRoom} intervals; a CHECKED_IN
     *       ReservationRoom is deliberately NOT counted, so a Room Change cannot leave the original room
     *       falsely blocked.</li>
     *   <li>CHECKED_IN Stays block through their actual {@code StayRoomAssignment}s converted to hotel dates:
     *       a closed assignment covers {@code [date(assignedFrom), date(assignedTo))}; an open assignment covers
     *       {@code [date(assignedFrom), max(planned check-out, today + 1))}. The hotel-zone Instant boundaries and
     *       the "current night" flag are computed in Java from the hotel Clock.</li>
     * </ul>
     *
     * @param roomIds Rooms to test
     * @param in inclusive first requested hotel night
     * @param out exclusive end of the requested interval
     * @param outStart start of the {@code out} hotel date in the hotel zone
     * @param inNextStart start of the hotel date after {@code in}, in the hotel zone
     * @param currentNightProtected {@code true} when the hotel current date is on or after {@code in}, so an open
     *     assignment also protects the current hotel night even past its planned check-out
     * @param confirmed the CONFIRMED Reservation status
     * @param stayCheckedIn the CHECKED_IN Stay status
     * @return identifiers of the Rooms that conflict
     */
    @Query(
            "SELECT r.id FROM Room r WHERE r.id IN :roomIds AND ("
                    + "EXISTS (SELECT 1 FROM ReservationRoom rr WHERE rr.room = r "
                    + "AND rr.reservation.status = :confirmed "
                    + "AND rr.checkInDate < :out AND rr.checkOutDate > :in) "
                    + "OR EXISTS (SELECT 1 FROM StayRoomAssignment a WHERE a.room = r "
                    + "AND a.stay.status = :stayCheckedIn AND a.stay.id <> :excludedStayId "
                    + "AND a.assignedFrom < :outStart "
                    + "AND ((a.assignedTo IS NOT NULL AND a.assignedTo >= :inNextStart) "
                    + "OR (a.assignedTo IS NULL AND (:currentNightProtected = TRUE "
                    + "OR a.stay.reservation.checkOutDate > :in)))))")
    List<UUID> findRoomIdsWithInventoryConflict(
            @Param("roomIds") Collection<UUID> roomIds,
            @Param("in") java.time.LocalDate in,
            @Param("out") java.time.LocalDate out,
            @Param("outStart") java.time.Instant outStart,
            @Param("inNextStart") java.time.Instant inNextStart,
            @Param("currentNightProtected") boolean currentNightProtected,
            @Param("confirmed") com.example.hotel.entity.booking.ReservationStatus confirmed,
            @Param("stayCheckedIn") com.example.hotel.entity.booking.StayStatus stayCheckedIn,
            @Param("excludedStayId") UUID excludedStayId);

    /**
     * Counts Rooms that remain in the active hotel inventory.
     *
     * @return active Room count
     */
    long countByActiveTrue();

    /**
     * Khóa các phòng theo thứ tự định danh để bảo vệ thao tác đồng thời.
     *
     * @param ids các định danh phòng cần khóa
     * @return các phòng đã được khóa
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id IN :ids ORDER BY r.id")
    List<Room> lockAllByIdIn(@Param("ids") Collection<UUID> ids);
}
