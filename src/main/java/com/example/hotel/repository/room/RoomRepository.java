package com.example.hotel.repository.room;

import com.example.hotel.entity.room.Room;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ và khóa đồng thời cho phòng. */
public interface RoomRepository extends JpaRepository<Room, UUID> {
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
