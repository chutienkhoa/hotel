package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves the derived Arrival Readiness and the authoritative {@link ReservationService#checkIn} agree: check-in is
 * accepted exactly when readiness has no BLOCKER, across every room state, timing, Stay and reservation-state case.
 */
class ArrivalReadinessCheckInConsistencyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms every room status and active flag: readiness blocks if and only if check-in rejects. */
    @Test
    void shouldAgreeForEveryRoomStateAndActiveFlag() {
        for (RoomStatus status : RoomStatus.values()) {
            for (boolean active : new boolean[] {true, false}) {
                assertAgreement(TODAY, true, status, active, false, 1, 2, status + " active=" + active);
            }
        }
    }

    /** Confirms early, normal and late arrivals agree (late is a warning only and check-in allows it). */
    @Test
    void shouldAgreeForEarlyNormalAndLateArrival() {
        assertAgreement(TODAY.plusDays(1), true, RoomStatus.AVAILABLE, true, false, 1, 2, "early");
        assertAgreement(TODAY, true, RoomStatus.AVAILABLE, true, false, 1, 2, "normal");
        assertAgreement(TODAY.minusDays(3), true, RoomStatus.AVAILABLE, true, false, 1, 2, "late");
    }

    /** Confirms a non-CONFIRMED reservation and an existing Stay agree. */
    @Test
    void shouldAgreeForNonConfirmedReservationAndExistingStay() {
        assertAgreement(TODAY, false, RoomStatus.AVAILABLE, true, false, 1, 2, "draft");
        assertAgreement(TODAY, true, RoomStatus.AVAILABLE, true, true, 1, 2, "stay exists");
    }

    /** Confirms adult capacity (sufficient, exact, insufficient, not configured) agrees between readiness and check-in. */
    @Test
    void shouldAgreeForAdultCapacityCases() {
        for (int adults : new int[] {1, 2, 3}) {
            for (Integer capacity : new Integer[] {1, 2, 3, null}) {
                assertAgreement(TODAY, true, RoomStatus.AVAILABLE, true, false, adults, capacity,
                        "adults=" + adults + " capacity=" + capacity);
            }
        }
    }

    private void assertAgreement(
            LocalDate checkIn, boolean confirmed, RoomStatus status, boolean active, boolean stayExists, int adults,
            Integer capacity, String label) {
        UUID reservationId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(roomId);
        when(room.isActive()).thenReturn(active);
        when(room.getStatus()).thenReturn(status);
        when(room.getRoomNumber()).thenReturn("101");
        RoomType roomType = mock(RoomType.class);
        when(roomType.getCapacity()).thenReturn(capacity);
        when(room.getRoomType()).thenReturn(roomType);
        Reservation reservation = new Reservation(
                reservationId, "R1", null, checkIn, checkIn.plusDays(2), adults, 0,
                com.example.hotel.entity.booking.BookingSource.DIRECT, null, "VND", null);
        reservation.addRoom(new ReservationRoom(
                reservation, room, checkIn, checkIn.plusDays(2), new BigDecimal("1000000")));
        reservation.calculateTotal();
        if (confirmed) {
            reservation.confirm();
        }
        ReservationRepository reservations = mock(ReservationRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        StayRepository stays = mock(StayRepository.class);
        ChargeRepository charges = mock(ChargeRepository.class);
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservations.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservations.findRoomIdsByReservationId(reservationId)).thenAnswer(invocation -> Optional.of(reservation).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));
        when(stays.existsByReservationId(reservationId)).thenReturn(stayExists);
        when(rooms.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(stays.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(charges.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "staff"), null, List.of()));
        ReservationService service = new ReservationService(
                reservations, mock(GuestRepository.class), rooms, stays, mock(StayRoomAssignmentRepository.class),
                charges, mock(AuditLogRepository.class), new ReservationMapper(), mock(ReservationNumberGenerator.class),
                mock(StayBalanceService.class), mock(com.example.hotel.service.room.RoomAvailabilityService.class), mock(com.example.hotel.service.booking.PrepaymentService.class),
                Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                reservation.getStatus(), checkIn, TODAY, stayExists, reservation.getAdultCount(), List.of(room), true);

        if (readiness.state() == ArrivalReadinessState.READY) {
            assertDoesNotThrow(() -> service.checkIn(reservationId), label + ": readiness READY but check-in rejected");
        } else {
            assertThrows(ResponseStatusException.class, () -> service.checkIn(reservationId),
                    label + ": readiness blocked but check-in accepted");
        }
        assertEquals(readiness.state() == ArrivalReadinessState.READY,
                readiness.blockers().isEmpty(), label);
    }
}
