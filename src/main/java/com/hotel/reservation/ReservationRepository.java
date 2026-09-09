package com.hotel.reservation;

import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** Cung cấp truy vấn lưu trữ và kiểm tra xung đột reservation. */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  /**
   * Kiểm tra xem một phòng có reservation thuộc các trạng thái được chỉ định bị giao ngày hay không.
   *
   * @param roomId định danh phòng
   * @param in ngày bắt đầu cần kiểm tra
   * @param out ngày kết thúc cần kiểm tra
   * @param statuses các trạng thái reservation được tính là đang chiếm phòng
   * @return {@code true} nếu tồn tại khoảng ngày giao nhau
   */
  @Query(
      "select count(rr)>0 from ReservationRoom rr where rr.room.id=:roomId and"
          + " rr.reservation.status in :statuses and rr.checkInDate < :out and rr.checkOutDate >"
          + " :in")
  boolean hasOverlap(
      @Param("roomId") UUID roomId,
      @Param("in") LocalDate in,
      @Param("out") LocalDate out,
      @Param("statuses") Collection<ReservationStatus> statuses);
}
