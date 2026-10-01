package com.example.hotel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.room.Room;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
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
        reservation.cancel(CancellationReasonCode.GUEST_REQUEST, null);
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertThrows(IllegalStateException.class, reservation::checkIn);
    }

    /** Confirms cancellation persists the structured reason and optional detail. */
    @Test
    void cancelPersistsReasonCodeAndDetail() {
        Reservation reservation = reservation();
        reservation.confirm();
        reservation.cancel(CancellationReasonCode.OTHER, "Guest called to cancel by phone");

        assertEquals(CancellationReasonCode.OTHER, reservation.getCancellationReasonCode());
        assertEquals("Guest called to cancel by phone", reservation.getCancellationReasonDetail());
    }

    /** Confirms no-show persists the required free-text reason. */
    @Test
    void noShowPersistsReason() {
        Reservation reservation = reservation();
        reservation.confirm();
        reservation.noShow("Guest did not arrive and could not be contacted.");

        assertEquals(ReservationStatus.NO_SHOW, reservation.getStatus());
        assertEquals("Guest did not arrive and could not be contacted.", reservation.getNoShowReason());
    }

    /** Confirms cancelling an already-terminal reservation cannot overwrite its recorded reason. */
    @Test
    void cancelReasonIsImmutableOnceCancelled() {
        Reservation reservation = reservation();
        reservation.confirm();
        reservation.cancel(CancellationReasonCode.GUEST_REQUEST, null);

        assertThrows(IllegalStateException.class,
                () -> reservation.cancel(CancellationReasonCode.OTHER, "different reason"));
        assertEquals(CancellationReasonCode.GUEST_REQUEST, reservation.getCancellationReasonCode());
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

    /** Confirms only a checked-in reservation may transition to checked out. */
    @Test
    void checkOutRequiresCheckedIn() {
        Reservation reservation = reservation();

        assertThrows(IllegalStateException.class, reservation::checkOut);
        reservation.confirm();
        reservation.checkIn();
        reservation.checkOut();

        assertEquals(ReservationStatus.CHECKED_OUT, reservation.getStatus());
        assertThrows(IllegalStateException.class, reservation::checkOut);
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
        reservation.cancel(CancellationReasonCode.GUEST_REQUEST, null);

        assertEquals(RESERVATION_NUMBER, reservation.getReservationNumber());
    }

    /** Confirms the selected source is retained by the newly created reservation. */
    @Test
    void shouldRetainSelectedBookingSource() {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), RESERVATION_NUMBER, null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12),
                BookingSource.BOOKING_COM, "VND", null);

        assertEquals(BookingSource.BOOKING_COM, reservation.getSource());
    }

    /** Confirms an OTA source stores the staff-entered booking reference verbatim. */
    @Test
    void shouldStoreOtaBookingReferenceVerbatimForOtaSource() {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), RESERVATION_NUMBER, null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12),
                BookingSource.AGODA, "AbC-123", "VND", null);

        assertEquals("AbC-123", reservation.getOtaBookingReference());
    }

    /** Confirms a DIRECT reservation never persists an OTA booking reference, even if supplied. */
    @Test
    void shouldDiscardOtaBookingReferenceForDirectSource() {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), RESERVATION_NUMBER, null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12),
                BookingSource.DIRECT, "STALE-REF", "VND", null);

        assertEquals(null, reservation.getOtaBookingReference());
    }

    /** Confirms switching an OTA draft's source back to DIRECT clears its stale OTA reference. */
    @Test
    void shouldClearOtaBookingReferenceWhenDraftSourceChangesToDirect() {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), RESERVATION_NUMBER, null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12),
                BookingSource.AGODA, "123456789", "VND", null);
        assertEquals("123456789", reservation.getOtaBookingReference());

        reservation.updateDraft(
                null,
                LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 12),
                BookingSource.DIRECT,
                "123456789",
                "VND",
                null,
                List.of());

        assertEquals(null, reservation.getOtaBookingReference());
    }

    /** Confirms editing an OTA draft's reference preserves the exact staff-entered text. */
    @Test
    void shouldUpdateOtaBookingReferenceWithoutTransformation() {
        Reservation reservation = new Reservation(
                UUID.randomUUID(), RESERVATION_NUMBER, null,
                LocalDate.of(2027, 1, 10), LocalDate.of(2027, 1, 12),
                BookingSource.AIRBNB, "hmabc123", "VND", null);

        reservation.updateDraft(
                null,
                LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 12),
                BookingSource.BOOKING_COM,
                "BK-987654",
                "VND",
                null,
                List.of());

        assertEquals("BK-987654", reservation.getOtaBookingReference());
    }

    /** Confirms draft editing replaces every room snapshot without changing its identity or state. */
    @Test
    void draftUpdateRebuildsRoomSnapshotsAndRecalculatesTotal() {
        Reservation reservation = reservation();
        String reservationNumber = reservation.getReservationNumber();
        ReservationRoom updatedRoom = new ReservationRoom(
                reservation,
                null,
                LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 2, 4),
                new BigDecimal("20000"));

        reservation.updateDraft(
                null,
                LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 2, 4),
                BookingSource.AIRBNB,
                "VND",
                "Updated notes",
                List.of(updatedRoom));

        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
        assertEquals(reservationNumber, reservation.getReservationNumber());
        assertEquals(BookingSource.AIRBNB, reservation.getSource());
        assertEquals(LocalDate.of(2027, 2, 1), reservation.getCheckInDate());
        assertEquals(LocalDate.of(2027, 2, 4), reservation.getCheckOutDate());
        assertEquals(0, new BigDecimal("60000").compareTo(reservation.getTotalAmount()));
        assertEquals(1, reservation.getRooms().size());
        assertEquals(LocalDate.of(2027, 2, 4), reservation.getRooms().getFirst().getCheckOutDate());
    }

    /** Confirms no non-draft Reservation can use the explicit draft update operation. */
    @Test
    void draftUpdateRejectsEveryNonDraftStatus() {
        for (ReservationStatus status : List.of(
                ReservationStatus.CONFIRMED,
                ReservationStatus.CHECKED_IN,
                ReservationStatus.CHECKED_OUT,
                ReservationStatus.CANCELLED,
                ReservationStatus.NO_SHOW)) {
            Reservation reservation = reservationInStatus(status);
            assertThrows(IllegalStateException.class, () -> reservation.updateDraft(
                    null,
                    reservation.getCheckInDate(),
                    reservation.getCheckOutDate(),
                    BookingSource.DIRECT,
                    "JPY",
                    null,
                    List.of()));
        }
    }

    /** Confirms repeated draft edits reuse the same room snapshot rather than duplicating it. */
    @Test
    void repeatedDraftUpdatesReconcileExistingRoomSnapshot() {
        Reservation reservation = reservation();
        Room room101 = Room.create(UUID.randomUUID(), "101", null, "1");
        reservation.addRoom(new ReservationRoom(
                reservation, room101, reservation.getCheckInDate(), reservation.getCheckOutDate(), new BigDecimal("1000000")));

        updateDraftWithRooms(reservation, List.of(new ReservationRoom(
                reservation, room101, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 3), new BigDecimal("1200000"))));
        updateDraftWithRooms(reservation, List.of(new ReservationRoom(
                reservation, room101, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 4), new BigDecimal("1300000"))));

        assertEquals(1, reservation.getRooms().size());
        assertEquals(room101.getId(), reservation.getRooms().getFirst().getRoom().getId());
        assertEquals(0, new BigDecimal("1300000").compareTo(reservation.getRooms().getFirst().getNightlyRate()));
        assertEquals(LocalDate.of(2027, 3, 1), reservation.getRooms().getFirst().getCheckInDate());
        assertEquals(0, new BigDecimal("3900000").compareTo(reservation.getTotalAmount()));
    }

    /** Confirms removed snapshots are orphaned and newly submitted rooms are added without duplicates. */
    @Test
    void draftUpdateReconcilesRemovedAndAddedRooms() {
        Reservation reservation = reservation();
        Room room101 = Room.create(UUID.randomUUID(), "101", null, "1");
        Room room102 = Room.create(UUID.randomUUID(), "102", null, "1");
        Room room103 = Room.create(UUID.randomUUID(), "103", null, "1");
        reservation.addRoom(new ReservationRoom(reservation, room101, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.ONE));
        reservation.addRoom(new ReservationRoom(reservation, room102, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.ONE));

        updateDraftWithRooms(reservation, List.of(
                new ReservationRoom(reservation, room101, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 3), new BigDecimal("20")),
                new ReservationRoom(reservation, room103, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 3), new BigDecimal("30"))));

        assertEquals(2, reservation.getRooms().size());
        assertEquals(List.of(room101.getId(), room103.getId()), reservation.getRooms().stream().map(room -> room.getRoom().getId()).toList());
        assertEquals(0, new BigDecimal("100").compareTo(reservation.getTotalAmount()));
    }

    private void updateDraftWithRooms(Reservation reservation, List<ReservationRoom> rooms) {
        reservation.updateDraft(
                null,
                rooms.getFirst().getCheckInDate(),
                rooms.getFirst().getCheckOutDate(),
                BookingSource.DIRECT,
                "JPY",
                null,
                rooms);
    }

    private Reservation reservationInStatus(ReservationStatus status) {
        Reservation reservation = reservation();
        reservation.confirm();
        if (status == ReservationStatus.CANCELLED) {
            reservation.cancel(CancellationReasonCode.GUEST_REQUEST, null);
        } else if (status == ReservationStatus.NO_SHOW) {
            reservation.noShow("Guest did not arrive and could not be contacted.");
        } else if (status == ReservationStatus.CHECKED_IN || status == ReservationStatus.CHECKED_OUT) {
            reservation.checkIn();
            if (status == ReservationStatus.CHECKED_OUT) {
                reservation.checkOut();
            }
        }
        return reservation;
    }
}
