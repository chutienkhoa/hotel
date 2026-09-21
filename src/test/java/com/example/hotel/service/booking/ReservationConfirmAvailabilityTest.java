package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies confirmation protects inventory by date overlap under a room lock, and is not blocked by the Room's
 * current operational status (a future booking is independent of what the room is doing today).
 */
class ReservationConfirmAvailabilityTest {

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final com.example.hotel.service.room.RoomAvailabilityService roomAvailability =
            mock(com.example.hotel.service.room.RoomAvailabilityService.class);
    private final ReservationService service = new ReservationService(
            reservations, mock(GuestRepository.class), rooms, mock(StayRepository.class),
            mock(StayRoomAssignmentRepository.class), mock(ChargeRepository.class), mock(AuditLogRepository.class),
            new ReservationMapper(), mock(ReservationNumberGenerator.class), mock(StayBalanceService.class), roomAvailability,
            Clock.systemDefaultZone());

    /** Clears the authentication established by the test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Reservation draft(Room room) {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R20261010-000001", null,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), "VND", null);
        reservation.addRoom(new ReservationRoom(
                reservation, room, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.ONE));
        reservation.calculateTotal();
        return reservation;
    }

    private Room roomWithStatus(RoomStatus status) {
        Room room = Room.create(UUID.randomUUID(), "101", capacityTwo(), "1");
        ReflectionTestUtils.setField(room, "status", status);
        return room;
    }

    private void arrange(Reservation reservation, Room room, boolean overlap) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "booking-manager"), null));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(rooms.lockAllByIdIn(List.of(room.getId()))).thenReturn(List.of(room));
        when(roomAvailability.conflictedRoomIds(
                        List.of(room.getId()), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12)))
                .thenReturn(overlap ? java.util.Set.of(room.getId()) : java.util.Set.of());
    }

    /**
     * Confirms a future reservation is confirmed for a room that is OCCUPIED, DIRTY or CLEANING today.
     *
     * @param status current operational status of the booked room
     */
    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"OCCUPIED", "DIRTY", "CLEANING", "AVAILABLE"})
    void shouldConfirmFutureReservationIndependentlyOfCurrentRoomStatus(RoomStatus status) {
        Room room = roomWithStatus(status);
        Reservation reservation = draft(room);
        arrange(reservation, room, false);

        var response = service.confirm(reservation.getId());

        assertEquals("CONFIRMED", response.status());
        verify(rooms).lockAllByIdIn(List.of(room.getId()));
    }

    /** Confirms an overlapping CONFIRMED/CHECKED_IN reservation still rejects confirmation under the room lock. */
    @Test
    void shouldRejectConfirmationWhenTheRequestedPeriodOverlaps() {
        Room room = roomWithStatus(RoomStatus.AVAILABLE);
        Reservation reservation = draft(room);
        arrange(reservation, room, true);

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.confirm(reservation.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
        verify(rooms).lockAllByIdIn(List.of(room.getId()));
        verify(reservations, org.mockito.Mockito.never()).save(any());
    }

    private static com.example.hotel.entity.room.RoomType capacityTwo() {
        com.example.hotel.entity.room.RoomType type = org.mockito.Mockito.mock(com.example.hotel.entity.room.RoomType.class);
        org.mockito.Mockito.when(type.getCapacity()).thenReturn(2);
        return type;
    }
}
