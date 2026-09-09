package com.hotel.reservation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Kiểm tra các quy tắc trạng thái và tính tiền của Reservation. */
class ReservationTest {
  /**
   * Tạo reservation nháp hợp lệ dùng chung cho test.
   *
   * @return reservation nháp
   */
  private Reservation reservation() {
    return new Reservation(
        UUID.randomUUID(), null, LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12), "JPY", null);
  }

  @Test
  /** Xác nhận reservation nháp chỉ được confirm một lần. */
  void draftCanOnlyConfirm() {
    Reservation r = reservation();
    r.confirm();
    assertEquals(ReservationStatus.CONFIRMED, r.getStatus());
    assertThrows(IllegalStateException.class, r::confirm);
  }

  @Test
  /** Xác nhận trạng thái đã hủy không được chuyển sang check-in. */
  void confirmedCanCancelOrNoShowButTerminalStatesCannotTransition() {
    Reservation r = reservation();
    r.confirm();
    r.cancel();
    assertEquals(ReservationStatus.CANCELLED, r.getStatus());
    assertThrows(IllegalStateException.class, r::checkIn);
  }

  @Test
  /** Xác nhận chỉ reservation đã confirm mới được check-in. */
  void checkInRequiresConfirmed() {
    Reservation r = reservation();
    assertThrows(IllegalStateException.class, r::checkIn);
    r.confirm();
    r.checkIn();
    assertEquals(ReservationStatus.CHECKED_IN, r.getStatus());
  }

  @Test
  /** Xác nhận tổng tiền sử dụng giá snapshot nhân với số đêm. */
  void totalsUseNightlySnapshotAndNumberOfNights() {
    Reservation r = reservation();
    r.addRoom(
        new ReservationRoom(
            r, null, r.getCheckInDate(), r.getCheckOutDate(), new BigDecimal("10000")));
    r.calculateTotal();
    assertEquals(0, new BigDecimal("20000").compareTo(r.getTotalAmount()));
  }
}
