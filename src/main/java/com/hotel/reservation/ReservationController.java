package com.hotel.reservation;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Cung cấp các API REST cho vòng đời reservation. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
  private final ReservationService service;

  /**
   * Tạo controller với dịch vụ reservation.
   *
   * @param s dịch vụ xử lý reservation
   */
  ReservationController(ReservationService s) {
    service = s;
  }

  /**
   * Tạo reservation nháp mới.
   *
   * @param request dữ liệu reservation cần tạo
   * @return reservation vừa tạo
   */
  @PostMapping
  @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
  ReservationDtos.Response create(@Valid @RequestBody ReservationDtos.CreateRequest request) {
    return service.create(request);
  }

  /**
   * Xác nhận reservation nháp.
   *
   * @param id định danh reservation
   * @return reservation sau khi xác nhận
   */
  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
  ReservationDtos.Response confirm(@PathVariable UUID id) {
    return service.confirm(id);
  }

  /**
   * Hủy reservation đã xác nhận.
   *
   * @param id định danh reservation
   * @return reservation sau khi hủy
   */
  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
  ReservationDtos.Response cancel(@PathVariable UUID id) {
    return service.cancel(id);
  }

  /**
   * Đánh dấu reservation đã xác nhận là no-show.
   *
   * @param id định danh reservation
   * @return reservation sau khi cập nhật
   */
  @PostMapping("/{id}/no-show")
  @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
  ReservationDtos.Response noShow(@PathVariable UUID id) {
    return service.noShow(id);
  }

  /**
   * Thực hiện check-in cho reservation.
   *
   * @param id định danh reservation
   * @return reservation sau khi check-in
   */
  @PostMapping("/{id}/check-in")
  @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
  ReservationDtos.Response checkIn(@PathVariable UUID id) {
    return service.checkIn(id);
  }
}
