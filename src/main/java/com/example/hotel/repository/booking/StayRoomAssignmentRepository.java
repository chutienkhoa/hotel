package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.StayRoomAssignment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
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

    /**
     * Finds the assignments whose interval overlaps {@code [start, end)}, as a narrow projection. Overlap
     * here is on Instants; expansion to hotel nights (and clipping) is done by the report.
     *
     * @param start inclusive start Instant of the report period
     * @param end exclusive end Instant of the report period
     * @return overlapping assignments ordered by start
     */
    @Query(
            "SELECT new com.example.hotel.repository.booking.StayRoomAssignmentNightRow("
                    + "a.originalReservationRoom.id, a.room.id, a.assignedFrom, a.assignedTo) "
                    + "FROM StayRoomAssignment a "
                    + "WHERE a.assignedFrom < :end AND (a.assignedTo IS NULL OR a.assignedTo > :start) "
                    + "ORDER BY a.assignedFrom, a.originalReservationRoom.id")
    List<StayRoomAssignmentNightRow> findNightRowsOverlapping(
            @Param("start") Instant start, @Param("end") Instant end);

    /**
     * Loads the OPEN (current) assignments of many Stays with their Room and RoomType in one query, ordered by room
     * number. Closed historical assignments are never returned.
     *
     * @param stayIds Stay identifiers
     * @return open assignments with Room and RoomType initialized
     */
    @Query("SELECT a FROM StayRoomAssignment a JOIN FETCH a.room room JOIN FETCH room.roomType "
            + "WHERE a.stay.id IN :stayIds AND a.assignedTo IS NULL ORDER BY room.roomNumber")
    List<StayRoomAssignment> findOpenByStayIdInWithRoom(@Param("stayIds") Collection<UUID> stayIds);
}
