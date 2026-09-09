package com.hotel.reservation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ cho các lần lưu trú. */
public interface StayRepository extends JpaRepository<Stay, UUID> {
  /**
   * Kiểm tra reservation đã có stay hay chưa.
   *
   * @param reservationId định danh reservation
   * @return {@code true} nếu stay đã tồn tại
   */
  boolean existsByReservationId(UUID reservationId);
}
