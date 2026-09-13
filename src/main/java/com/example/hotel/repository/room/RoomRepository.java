package com.example.hotel.repository.room;

import com.example.hotel.entity.room.Room;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ và khóa đồng thời cho phòng. */
public interface RoomRepository extends JpaRepository<Room, UUID> {
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
