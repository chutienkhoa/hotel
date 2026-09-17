package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.StayRoomAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ và truy vấn cho lịch sử chiếm phòng thực tế của lưu trú. */
public interface StayRoomAssignmentRepository extends JpaRepository<StayRoomAssignment, UUID> {

    /**
     * Tìm assignment đang mở (phòng hiện tại) của một lưu trú cho một phòng cụ thể.
     *
     * @param stayId định danh lưu trú
     * @param roomId định danh phòng hiện tại
     * @return assignment đang mở, nếu tồn tại
     */
    @Query(
            "SELECT a FROM StayRoomAssignment a "
                    + "WHERE a.stay.id = :stayId "
                    + "AND a.room.id = :roomId "
                    + "AND a.assignedTo IS NULL")
    Optional<StayRoomAssignment> findOpenByStayIdAndRoomId(
            @Param("stayId") UUID stayId, @Param("roomId") UUID roomId);

    /**
     * Tìm mọi assignment đang mở (các phòng hiện tại) của một lưu trú.
     *
     * @param stayId định danh lưu trú
     * @return danh sách assignment đang mở, sắp xếp theo phòng
     */
    @Query(
            "SELECT a FROM StayRoomAssignment a "
                    + "WHERE a.stay.id = :stayId "
                    + "AND a.assignedTo IS NULL "
                    + "ORDER BY a.room.roomNumber")
    List<StayRoomAssignment> findOpenByStayId(@Param("stayId") UUID stayId);

    /**
     * Tìm toàn bộ lịch sử chiếm phòng (mở và đã đóng) của một lưu trú, theo thứ tự thời gian.
     *
     * @param stayId định danh lưu trú
     * @return danh sách assignment theo thứ tự thời gian bắt đầu
     */
    @Query(
            "SELECT a FROM StayRoomAssignment a "
                    + "WHERE a.stay.id = :stayId "
                    + "ORDER BY a.originalReservationRoom.id, a.assignedFrom")
    List<StayRoomAssignment> findByStayIdOrderByLineageAndTime(@Param("stayId") UUID stayId);
}
