package com.example.hotel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Kiểm tra các quy tắc trạng thái và tính tiền của Reservation. */
class ReservationTest {
    private static final String RESERVATION_NUMBER = "R20270110-000001";

    /**
     * Tạo reservation nháp hợp lệ dùng chung cho test.
     *
     * @return reservation nháp
     */
    private Reservation reservation() {
        return new Reservation(
                UUID.randomUUID(),
                RESERVATION_NUMBER,
                null,
                LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 12),
                "JPY",
                null);
    }

    /** Xác nhận reservation nháp chỉ được confirm một lần. */
    @Test
    void draftCanOnlyConfirm() {
        Reservation reservation = reservation();
        reservation.confirm();
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertThrows(IllegalStateException.class, reservation::confirm);
    }

    /** Xác nhận trạng thái đã hủy không được chuyển sang check-in. */
    @Test
    void confirmedCanCancelOrNoShowButTerminalStatesCannotTransition() {
        Reservation reservation = reservation();
        reservation.confirm();
        reservation.cancel();
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertThrows(IllegalStateException.class, reservation::checkIn);
    }

    /** Xác nhận chỉ reservation đã confirm mới được check-in. */
    @Test
    void checkInRequiresConfirmed() {
        Reservation reservation = reservation();
        assertThrows(IllegalStateException.class, reservation::checkIn);
        reservation.confirm();
        reservation.checkIn();
        assertEquals(ReservationStatus.CHECKED_IN, reservation.getStatus());
    }

    /** Xác nhận tổng tiền sử dụng giá snapshot nhân với số đêm. */
    @Test
    void totalsUseNightlySnapshotAndNumberOfNights() {
        Reservation reservation = reservation();
        reservation.addRoom(
                new ReservationRoom(
                        reservation,
                        null,
                        reservation.getCheckInDate(),
                        reservation.getCheckOutDate(),
                        new BigDecimal("10000")));
        reservation.calculateTotal();
        assertEquals(0, new BigDecimal("20000").compareTo(reservation.getTotalAmount()));
    }

    /** Confirms that state changes do not alter the backend-generated reservation number. */
    @Test
    void reservationNumberIsImmutableAfterCreation() {
        Reservation reservation = reservation();

        reservation.confirm();
        reservation.cancel();

        assertEquals(RESERVATION_NUMBER, reservation.getReservationNumber());
    }
}
