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
