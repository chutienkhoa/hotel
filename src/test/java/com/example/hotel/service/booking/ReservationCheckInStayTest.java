package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Verifies Reservation check-in persists the approved initial Stay state. */
class ReservationCheckInStayTest {

    /** Clears the authentication established by the test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms check-in creates exactly one checked-in Stay with backend-managed timestamps. */
    @Test
    void shouldCreateCheckedInStayWhenCheckingInReservation() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ReservationNumberGenerator reservationNumberGenerator = mock(ReservationNumberGenerator.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = mock(Room.class);
        Reservation reservation = checkedInReservationCandidate(reservationId, room);

        setCurrentUser(userId);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.existsByReservationId(reservationId)).thenReturn(false);
        when(room.getId()).thenReturn(roomId);
        when(room.isActive()).thenReturn(true);
        when(room.getStatus()).thenReturn(RoomStatus.AVAILABLE);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(stayRepository.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reservationService(
                        reservationRepository,
                        guestRepository,
                        roomRepository,
                        stayRepository,
                        auditLogRepository,
                        reservationNumberGenerator,
                        stayBalanceService)
                .checkIn(reservationId);

        ArgumentCaptor<Stay> stayCaptor = ArgumentCaptor.forClass(Stay.class);
        verify(stayRepository).save(stayCaptor.capture());
        Stay savedStay = stayCaptor.getValue();
        assertEquals(reservation, savedStay.getReservation());
        assertEquals(StayStatus.CHECKED_IN, savedStay.getStatus());
        assertNotNull(savedStay.getActualCheckInAt());
        assertNull(savedStay.getActualCheckOutAt());
        assertEquals(userId, savedStay.getCreatedBy());
        assertEquals(userId, savedStay.getUpdatedBy());
        verify(room).occupy();
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    /**
     * Creates a confirmed reservation with one assigned Room for a check-in test.
     *
     * @param reservationId reservation identifier
     * @param room assigned room
     * @return confirmed reservation ready for check-in
     */
    private Reservation checkedInReservationCandidate(UUID reservationId, Room room) {
        Reservation reservation = new Reservation(
                reservationId,
                "R20260911-000001",
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                "JPY",
                null);
        reservation.addRoom(
                new ReservationRoom(
                        reservation,
                        room,
                        reservation.getCheckInDate(),
                        reservation.getCheckOutDate(),
                        BigDecimal.ONE));
        reservation.calculateTotal();
        reservation.confirm();
        return reservation;
    }

    /**
     * Creates the Reservation service under test from mocked persistence collaborators.
     *
     * @param reservationRepository reservation repository
     * @param guestRepository guest repository
     * @param roomRepository room repository
     * @param stayRepository stay repository
     * @param auditLogRepository audit-log repository
     * @param reservationNumberGenerator reservation-number generator
     * @param stayBalanceService service used for check-out balance calculation
     * @return configured reservation service
     */
    private ReservationService reservationService(
            ReservationRepository reservationRepository,
            GuestRepository guestRepository,
            RoomRepository roomRepository,
            StayRepository stayRepository,
            AuditLogRepository auditLogRepository,
            ReservationNumberGenerator reservationNumberGenerator,
            StayBalanceService stayBalanceService) {
        return new ReservationService(
                reservationRepository,
                guestRepository,
                roomRepository,
                stayRepository,
                auditLogRepository,
                new ReservationMapper(),
                reservationNumberGenerator,
                stayBalanceService);
    }

    /**
     * Establishes the application principal used by the check-in service operation.
     *
     * @param userId authenticated application-user identifier
     */
    private void setCurrentUser(UUID userId) {
        CurrentUser user = new CurrentUser(userId, "check-in-user");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }
}
