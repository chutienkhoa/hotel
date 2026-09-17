package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the atomic Reservation check-out use case and its required preconditions. */
class ReservationCheckOutServiceTest {

    /** Clears the authenticated user established for each service operation. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms exact payment produces a complete Reservation, Stay, and Room check-out. */
    @Test
    void shouldCheckOutCheckedInReservationWithZeroOutstanding() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED));
        Instant actualCheckInAt = fixture.stay().getActualCheckInAt();
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        Response response = fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(ReservationStatus.CHECKED_OUT, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_OUT, fixture.stay().getStatus());
        assertEquals(actualCheckInAt, fixture.stay().getActualCheckInAt());
        assertNotNull(fixture.stay().getActualCheckOutAt());
        assertEquals("CHECKED_OUT", response.status());
        fixture.rooms().forEach(room -> assertEquals(RoomStatus.DIRTY, room.getStatus()));
        fixture.rooms().forEach(room -> assertEquals(fixture.userId(), room.getUpdatedBy()));
        assertEquals(fixture.creatorId(), fixture.reservation().getCreatedBy());
        assertEquals(fixture.userId(), fixture.reservation().getUpdatedBy());
        assertEquals(fixture.creatorId(), fixture.stay().getCreatedBy());
        assertEquals(fixture.userId(), fixture.stay().getUpdatedBy());
        verify(fixture.roomRepository()).lockAllByIdIn(fixture.roomIds());
        verify(fixture.auditLogRepository()).save(any(AuditLog.class));
    }

    /** Confirms a Reservation outside CHECKED_IN cannot be checked out. */
    @Test
    void shouldRejectReservationOutsideCheckedInState() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        fixture.reservation().checkOut();

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms a Reservation without its required unique Stay cannot be checked out. */
    @Test
    void shouldRejectReservationWithoutStay() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayRepository().findByReservationIdForUpdate(fixture.reservation().getId()))
                .thenReturn(Optional.empty());

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms only a checked-in Stay can participate in a Reservation check-out. */
    @Test
    void shouldRejectCheckedOutStay() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        fixture.stay().checkOut();

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms any positive outstanding amount prevents check-out before Room mutations. */
    @Test
    void shouldRejectOutstandingBalanceBeforeChangingStates() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.ZERO));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_IN, fixture.stay().getStatus());
        assertEquals(RoomStatus.OCCUPIED, fixture.rooms().getFirst().getStatus());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(any());
    }

    /** Confirms non-contributing Payment statuses leave a positive outstanding balance. */
    @ParameterizedTest
    @MethodSource("nonContributingPaymentStatuses")
    void shouldRejectCheckoutWhenNonPaidPaymentDoesNotSettleBalance(String paymentStatus) {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.ZERO));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus(), paymentStatus);
    }

    /** Confirms a non-occupied assigned Room fails check-out without partially changing any state. */
    @Test
    void shouldRejectNonOccupiedRoomWithoutPartialCheckout() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.AVAILABLE));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(RoomStatus.OCCUPIED, fixture.rooms().getFirst().getStatus());
        assertEquals(RoomStatus.AVAILABLE, fixture.rooms().get(1).getStatus());
        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_IN, fixture.stay().getStatus());
        verify(fixture.auditLogRepository(), never()).save(any(AuditLog.class));
    }

    /** Supplies Payment statuses that the balance service excludes from total paid payments. */
    private static Stream<Arguments> nonContributingPaymentStatuses() {
        return Stream.of(
                Arguments.of("PENDING"), Arguments.of("FAILED"), Arguments.of("REFUNDED"));
    }

    /** Creates a zero- or positive-outstanding balance fixture. */
    private StayBalance balance(BigDecimal totalCharges, BigDecimal totalPaidPayments) {
        return new StayBalance(
                totalCharges,
                totalPaidPayments,
                totalCharges.subtract(totalPaidPayments));
    }

    /** Creates a complete checked-in Reservation fixture with rooms in the requested statuses. */
    private Fixture fixture(List<RoomStatus> roomStatuses) {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ReservationNumberGenerator reservationNumberGenerator = mock(ReservationNumberGenerator.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        UUID reservationId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Reservation reservation = new Reservation(
                reservationId,
                "R20260911-000001",
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                "JPY",
                null);
        List<Room> rooms = new ArrayList<>();
        for (int index = 0; index < roomStatuses.size(); index++) {
            Room room = Room.create(UUID.randomUUID(), "10" + index, null, "1");
            if (roomStatuses.get(index) == RoomStatus.OCCUPIED) {
                room.occupy();
            }
            room.audit(creatorId);
            rooms.add(room);
            reservation.addRoom(new ReservationRoom(
                    reservation,
                    room,
                    reservation.getCheckInDate(),
                    reservation.getCheckOutDate(),
                    BigDecimal.TEN));
        }
        reservation.calculateTotal();
        reservation.confirm();
        reservation.checkIn();
        reservation.audit(creatorId);
        Stay stay = new Stay(reservation);
        stay.audit(creatorId);
        List<UUID> roomIds = rooms.stream().map(Room::getId).sorted().toList();
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.findByReservationIdForUpdate(reservationId)).thenReturn(Optional.of(stay));
        when(roomRepository.lockAllByIdIn(roomIds)).thenReturn(rooms.stream().sorted(
                java.util.Comparator.comparing(Room::getId)).toList());
        setCurrentUser(userId);
        return new Fixture(
                new ReservationService(
                        reservationRepository,
                        guestRepository,
                        roomRepository,
                        stayRepository,
                        chargeRepository,
                        auditLogRepository,
                        new ReservationMapper(),
                        reservationNumberGenerator,
                        stayBalanceService,
                        Clock.systemDefaultZone()),
                reservationRepository,
                roomRepository,
                stayRepository,
                auditLogRepository,
                stayBalanceService,
                reservation,
                stay,
                rooms,
                roomIds,
                creatorId,
                userId);
    }

    /** Establishes the authenticated application user for backend audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "check-out-user"), null));
    }

    /** Verifies the standard conflict response used for failed check-out preconditions. */
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(409, exception.getStatusCode().value());
    }

    /** Holds the collaborators and persisted entities used by a check-out scenario. */
    private record Fixture(
            ReservationService service,
            ReservationRepository reservationRepository,
            RoomRepository roomRepository,
            StayRepository stayRepository,
            AuditLogRepository auditLogRepository,
            StayBalanceService stayBalanceService,
            Reservation reservation,
            Stay stay,
            List<Room> rooms,
            List<UUID> roomIds,
            UUID creatorId,
            UUID userId) {}
}
