package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
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
 * Verifies the Early/Normal/Late Check-in date rule enforced inside
 * {@link ReservationService#checkIn}, independent of any UI/page controller, so a direct
 * POST/API invocation cannot bypass it.
 */
class ReservationCheckInTimingTest {

    private static final LocalDate SCHEDULED_CHECK_IN = LocalDate.of(2026, 9, 15);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms check-in is blocked before the scheduled check-in date, with no side effects. */
    @Test
    void shouldRejectEarlyCheckIn() {
        Fixture fixture = fixture(clockOn(SCHEDULED_CHECK_IN.minusDays(1)));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> fixture.service.checkIn(fixture.reservationId));

        assertEquals(true, exception.getReason().toLowerCase().contains("early check-in is not allowed"));
        verifyNoInteractions(fixture.stayRepository);
        verifyNoInteractions(fixture.chargeRepository);
        verify(fixture.roomRepository, never()).lockAllByIdIn(any());
    }

    /** Confirms check-in succeeds normally on the scheduled check-in date. */
    @Test
    void shouldAllowCheckInOnScheduledDate() {
        Fixture fixture = fixture(clockOn(SCHEDULED_CHECK_IN));

        fixture.service.checkIn(fixture.reservationId);

        verify(fixture.stayRepository).save(any(Stay.class));
    }

    /** Confirms the Stay's actualCheckInAt comes from the injected hotel Clock, exactly. */
    @Test
    void shouldCreateStayWithActualCheckInAtFromTheInjectedClock() {
        Clock clock = clockOn(SCHEDULED_CHECK_IN);
        Fixture fixture = fixture(clock);

        fixture.service.checkIn(fixture.reservationId);

        org.mockito.ArgumentCaptor<Stay> captor = org.mockito.ArgumentCaptor.forClass(Stay.class);
        verify(fixture.stayRepository).save(captor.capture());
        assertEquals(clock.instant(), captor.getValue().getActualCheckInAt());
        assertEquals(SCHEDULED_CHECK_IN.atTime(10, 0).atZone(ZONE).toInstant(), captor.getValue().getActualCheckInAt());
    }

    /**
     * Confirms check-in succeeds after the scheduled date (late) and the ROOM Charge is still
     * derived exclusively from the original ReservationRoom snapshot: unchanged dates, nights,
     * nightly rate, and total, never from the actual (late) check-in time.
     */
    @Test
    void shouldAllowLateCheckInAndPreserveOriginalSnapshotAndCharge() {
        Fixture fixture = fixture(clockOn(SCHEDULED_CHECK_IN.plusDays(3)));

        fixture.service.checkIn(fixture.reservationId);

        ReservationRoom room = fixture.reservation.getRooms().get(0);
        assertEquals(SCHEDULED_CHECK_IN, fixture.reservation.getCheckInDate());
        assertEquals(SCHEDULED_CHECK_IN.plusDays(2), fixture.reservation.getCheckOutDate());
        assertEquals(SCHEDULED_CHECK_IN, room.getCheckInDate());
        assertEquals(SCHEDULED_CHECK_IN.plusDays(2), room.getCheckOutDate());
        assertEquals(0, new BigDecimal("1000000").compareTo(room.getNightlyRate()));
        assertEquals(0, new BigDecimal("2000000").compareTo(room.getTotalAmount()));

        org.mockito.ArgumentCaptor<Charge> chargeCaptor = org.mockito.ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository).save(chargeCaptor.capture());
        Charge savedCharge = chargeCaptor.getValue();
        assertEquals(0, new BigDecimal("2000000").compareTo(savedCharge.getAmount()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo(savedCharge.getQuantity()));
    }

    /**
     * Builds a fixed Clock reporting the supplied business date at a stable time of day.
     *
     * @param date the LocalDate the Clock should report as "today"
     * @return a fixed Clock
     */
    private Clock clockOn(LocalDate date) {
        return Clock.fixed(date.atTime(10, 0).atZone(ZONE).toInstant(), ZONE);
    }

    /**
     * Builds a fully mocked CONFIRMED Reservation ready for a Check-in timing test.
     *
     * @param clock the business clock the service under test should use
     * @return the wired fixture
     */
    private Fixture fixture(Clock clock) {
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
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        new CurrentUser(userId, "staff"), null, List.of()));

        UUID reservationId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(roomId);
        when(room.isActive()).thenReturn(true);
        when(room.getStatus()).thenReturn(RoomStatus.AVAILABLE);
        when(room.getRoomNumber()).thenReturn("101");

        Reservation reservation = new Reservation(
                reservationId,
                "R20260915-000001",
                null,
                SCHEDULED_CHECK_IN,
                SCHEDULED_CHECK_IN.plusDays(2),
                "VND",
                null);
        reservation.addRoom(new ReservationRoom(
                reservation, room, reservation.getCheckInDate(), reservation.getCheckOutDate(), new BigDecimal("1000000")));
        reservation.calculateTotal();
        reservation.confirm();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.existsByReservationId(reservationId)).thenReturn(false);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(stayRepository.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReservationService service = new ReservationService(
                reservationRepository,
                guestRepository,
                roomRepository,
                stayRepository,
                stayRoomAssignmentRepository,
                chargeRepository,
                auditLogRepository,
                new ReservationMapper(),
                reservationNumberGenerator,
                stayBalanceService,
                clock);

        return new Fixture(service, reservation, reservationId, stayRepository, chargeRepository, roomRepository);
    }

    /** Bundles a wired ReservationService with the fixtures a timing test needs to inspect. */
    private record Fixture(
            ReservationService service,
            Reservation reservation,
            UUID reservationId,
            StayRepository stayRepository,
            ChargeRepository chargeRepository,
            RoomRepository roomRepository) {}
}
