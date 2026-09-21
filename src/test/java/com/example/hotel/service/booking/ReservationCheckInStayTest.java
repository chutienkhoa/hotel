package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
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
        StayRoomAssignmentRepository stayRoomAssignmentRepository = mock(StayRoomAssignmentRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
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
        when(room.getRoomNumber()).thenReturn("101");
        com.example.hotel.entity.room.RoomType roomType = capacityTwo();
        when(room.getRoomType()).thenReturn(roomType);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(stayRepository.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reservationService(
                        reservationRepository,
                        guestRepository,
                        roomRepository,
                        stayRepository,
                        stayRoomAssignmentRepository,
                        chargeRepository,
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

        ArgumentCaptor<com.example.hotel.entity.booking.StayRoomAssignment> assignmentCaptor =
                ArgumentCaptor.forClass(com.example.hotel.entity.booking.StayRoomAssignment.class);
        verify(stayRoomAssignmentRepository).save(assignmentCaptor.capture());
        com.example.hotel.entity.booking.StayRoomAssignment savedAssignment = assignmentCaptor.getValue();
        assertEquals(savedStay, savedAssignment.getStay());
        assertEquals(room, savedAssignment.getRoom());
        assertEquals(reservation.getRooms().get(0), savedAssignment.getOriginalReservationRoom());
        assertEquals(savedStay.getActualCheckInAt(), savedAssignment.getAssignedFrom());
        assertNull(savedAssignment.getAssignedTo());
        assertNull(savedAssignment.getReason());
        assertEquals(userId, savedAssignment.getCreatedBy());

        ArgumentCaptor<Charge> chargeCaptor = ArgumentCaptor.forClass(Charge.class);
        verify(chargeRepository).save(chargeCaptor.capture());
        Charge savedCharge = chargeCaptor.getValue();
        assertEquals(savedStay, savedCharge.getStay());
        assertEquals(ChargeType.ROOM, savedCharge.getType());
        assertEquals("Room 101", savedCharge.getDescription());
        assertEquals(0, reservation.getRooms().get(0).getTotalAmount().compareTo(savedCharge.getAmount()));
        assertEquals(userId, savedCharge.getCreatedBy());
    }

    /** Confirms multi-room check-in seeds one independent open assignment lineage per ReservationRoom. */
    @Test
    void shouldSeedIndependentLineagePerRoomOnMultiRoomCheckIn() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        StayRoomAssignmentRepository stayRoomAssignmentRepository = mock(StayRoomAssignmentRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ReservationNumberGenerator reservationNumberGenerator = mock(ReservationNumberGenerator.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Room roomA = Room.create(UUID.randomUUID(), "201", capacityTwo(), "2");
        Room roomB = Room.create(UUID.randomUUID(), "202", capacityTwo(), "2");
        Reservation reservation = new Reservation(
                reservationId, "R20260911-000002", null, LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 13), "JPY", null);
        ReservationRoom reservationRoomA = new ReservationRoom(
                reservation, roomA, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.ONE);
        ReservationRoom reservationRoomB = new ReservationRoom(
                reservation, roomB, reservation.getCheckInDate(), reservation.getCheckOutDate(), BigDecimal.TEN);
        reservation.addRoom(reservationRoomA);
        reservation.addRoom(reservationRoomB);
        reservation.calculateTotal();
        reservation.confirm();

        List<UUID> roomIds = List.of(roomA.getId(), roomB.getId()).stream().sorted().toList();
        setCurrentUser(userId);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.existsByReservationId(reservationId)).thenReturn(false);
        when(roomRepository.lockAllByIdIn(roomIds)).thenReturn(
                List.of(roomA, roomB).stream().sorted(java.util.Comparator.comparing(Room::getId)).toList());
        when(stayRepository.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reservationService(
                        reservationRepository,
                        guestRepository,
                        roomRepository,
                        stayRepository,
                        stayRoomAssignmentRepository,
                        chargeRepository,
                        auditLogRepository,
                        reservationNumberGenerator,
                        stayBalanceService)
                .checkIn(reservationId);

        ArgumentCaptor<com.example.hotel.entity.booking.StayRoomAssignment> assignmentCaptor =
                ArgumentCaptor.forClass(com.example.hotel.entity.booking.StayRoomAssignment.class);
        verify(stayRoomAssignmentRepository, org.mockito.Mockito.times(2)).save(assignmentCaptor.capture());
        List<com.example.hotel.entity.booking.StayRoomAssignment> savedAssignments = assignmentCaptor.getAllValues();
        assertEquals(2, savedAssignments.size());
        assertEquals(roomA, savedAssignments.get(0).getRoom());
        assertEquals(reservationRoomA, savedAssignments.get(0).getOriginalReservationRoom());
        assertEquals(roomB, savedAssignments.get(1).getRoom());
        assertEquals(reservationRoomB, savedAssignments.get(1).getOriginalReservationRoom());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                savedAssignments.get(0).getOriginalReservationRoom(),
                savedAssignments.get(1).getOriginalReservationRoom(),
                "each lineage must reference its own ReservationRoom, not a shared or swapped one");
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
            StayRoomAssignmentRepository stayRoomAssignmentRepository,
            ChargeRepository chargeRepository,
            AuditLogRepository auditLogRepository,
            ReservationNumberGenerator reservationNumberGenerator,
            StayBalanceService stayBalanceService) {
        return new ReservationService(
                reservationRepository,
                guestRepository,
                roomRepository,
                stayRepository,
                stayRoomAssignmentRepository,
                chargeRepository,
                auditLogRepository,
                new ReservationMapper(),
                reservationNumberGenerator,
                stayBalanceService, mock(com.example.hotel.service.room.RoomAvailabilityService.class),
                Clock.systemDefaultZone());
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

    private static com.example.hotel.entity.room.RoomType capacityTwo() {
        com.example.hotel.entity.room.RoomType type = org.mockito.Mockito.mock(com.example.hotel.entity.room.RoomType.class);
        org.mockito.Mockito.when(type.getCapacity()).thenReturn(2);
        return type;
    }
}
