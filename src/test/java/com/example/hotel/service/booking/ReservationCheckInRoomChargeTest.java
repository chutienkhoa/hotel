package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies automatic ROOM Charge creation on successful Reservation check-in. */
class ReservationCheckInRoomChargeTest {

    /** Clears the authentication established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms one ROOM Charge per ReservationRoom is created, not one aggregate Charge. */
    @Test
    void shouldCreateOneRoomChargePerReservationRoom() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));

        fixture.reservationService().checkIn(fixture.reservationId());

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository(), times(2)).save(captor.capture());
        List<Charge> savedCharges = captor.getAllValues();
        assertEquals(2, savedCharges.size());
        for (Charge charge : savedCharges) {
            assertEquals(ChargeType.ROOM, charge.getType());
        }
    }

    /** Confirms each Charge amount is copied exactly from ReservationRoom.totalAmount, not recalculated. */
    @Test
    void shouldPreserveReservationRoomTotalAmountSnapshotAsChargeAmount() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));

        fixture.reservationService().checkIn(fixture.reservationId());

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository(), times(2)).save(captor.capture());
        List<BigDecimal> chargeAmounts = captor.getAllValues().stream().map(Charge::getAmount).toList();
        List<BigDecimal> expectedAmounts = fixture.reservation().getRooms().stream()
                .map(ReservationRoom::getTotalAmount)
                .toList();
        assertEquals(expectedAmounts.size(), chargeAmounts.size());
        for (int index = 0; index < expectedAmounts.size(); index++) {
            assertEquals(0, expectedAmounts.get(index).compareTo(chargeAmounts.get(index)));
        }
    }

    /**
     * Confirms the sum of automatic ROOM Charge amounts equals Reservation.totalAmount, holding the
     * invariant regardless of the Reservation currency (VND here; USD verified separately).
     */
    @Test
    void shouldMakeRoomChargeSumEqualReservationTotalAmount() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));

        fixture.reservationService().checkIn(fixture.reservationId());

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository(), times(2)).save(captor.capture());
        BigDecimal sum = captor.getAllValues().stream()
                .map(Charge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, fixture.reservation().getTotalAmount().compareTo(sum));
    }

    /** Confirms a USD Reservation produces ROOM Charge amounts still denominated in USD, unconverted. */
    @Test
    void shouldKeepRoomChargeAmountInReservationCurrencyWithoutConversion() {
        Fixture fixture = fixture("USD", new BigDecimal("40.00"), new BigDecimal("50.00"));

        fixture.reservationService().checkIn(fixture.reservationId());

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository(), times(2)).save(captor.capture());
        List<BigDecimal> chargeAmounts = captor.getAllValues().stream().map(Charge::getAmount).toList();
        List<BigDecimal> expectedAmounts = fixture.reservation().getRooms().stream()
                .map(ReservationRoom::getTotalAmount)
                .toList();
        for (int index = 0; index < expectedAmounts.size(); index++) {
            assertEquals(0, expectedAmounts.get(index).compareTo(chargeAmounts.get(index)));
        }
        assertEquals("USD", fixture.reservation().getCurrency());
    }

    /** Confirms each Charge description identifies the specific room it belongs to. */
    @Test
    void shouldDescribeEachRoomChargeWithItsRoomNumber() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));

        fixture.reservationService().checkIn(fixture.reservationId());

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(fixture.chargeRepository(), times(2)).save(captor.capture());
        List<String> descriptions = captor.getAllValues().stream().map(Charge::getDescription).toList();
        assertEquals(List.of("Room 101", "Room 202"), descriptions);
    }

    /**
     * Confirms a persistence failure while creating a ROOM Charge propagates out of checkIn() so the
     * surrounding {@code @Transactional} boundary rolls back the Reservation, Room, and Stay changes
     * made earlier in the same method. Verifying the actual database rollback requires a running
     * transactional Spring context, which this focused unit test does not stand up; propagation of the
     * failure out of checkIn() is what makes Spring's declarative rollback apply.
     */
    @Test
    void shouldPropagateFailureWhenRoomChargeCreationFails() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));
        when(fixture.chargeRepository().save(any(Charge.class))).thenThrow(new RuntimeException("db failure"));

        assertThrows(RuntimeException.class, () -> fixture.reservationService().checkIn(fixture.reservationId()));
    }

    /** Confirms a second check-in attempt on an already checked-in Reservation creates no new Charges. */
    @Test
    void shouldNotCreateAdditionalRoomChargesOnRetriedCheckIn() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));

        fixture.reservationService().checkIn(fixture.reservationId());
        when(fixture.stayRepository().existsByReservationId(fixture.reservationId())).thenReturn(true);

        assertThrows(
                ResponseStatusException.class,
                () -> fixture.reservationService().checkIn(fixture.reservationId()));
        verify(fixture.chargeRepository(), times(2)).save(any(Charge.class));
    }

    /** Confirms an invalid Reservation state produces zero Charges. */
    @Test
    void shouldNotCreateRoomChargesForInvalidReservationState() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));
        fixture.reservation().cancel();

        assertThrows(
                ResponseStatusException.class,
                () -> fixture.reservationService().checkIn(fixture.reservationId()));
        verify(fixture.chargeRepository(), never()).save(any(Charge.class));
    }

    /** Confirms an unavailable Room produces zero Charges. */
    @Test
    void shouldNotCreateRoomChargesWhenRoomIsUnavailable() {
        Fixture fixture = fixture("VND", new BigDecimal("1000000.00"), new BigDecimal("1200000.00"));
        for (Room room : fixture.rooms()) {
            when(room.getStatus()).thenReturn(RoomStatus.OCCUPIED);
        }

        assertThrows(
                ResponseStatusException.class,
                () -> fixture.reservationService().checkIn(fixture.reservationId()));
        verify(fixture.chargeRepository(), never()).save(any(Charge.class));
    }

    /**
     * Builds a confirmed two-room Reservation ready for check-in, with mocked Room and repository
     * collaborators wired for a successful check-in path.
     *
     * @param currency reservation currency code
     * @param firstNightlyRate nightly-rate snapshot for the first room (3-night stay)
     * @param secondNightlyRate nightly-rate snapshot for the second room (3-night stay)
     * @return the assembled test fixture
     */
    private Fixture fixture(String currency, BigDecimal firstNightlyRate, BigDecimal secondNightlyRate) {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        StayRoomAssignmentRepository stayRoomAssignmentRepository = mock(StayRoomAssignmentRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ReservationNumberGenerator reservationNumberGenerator = mock(ReservationNumberGenerator.class);
        StayBalanceService stayBalanceService =
                new StayBalanceService(chargeRepository, paymentRepository);

        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        LocalDate checkIn = LocalDate.of(2026, 9, 11);
        LocalDate checkOut = checkIn.plusDays(3);

        Reservation reservation = new Reservation(
                reservationId, "R20260911-000001", null, checkIn, checkOut, currency, null);

        Room firstRoom = mock(Room.class);
        UUID firstRoomId = UUID.randomUUID();
        when(firstRoom.getId()).thenReturn(firstRoomId);
        when(firstRoom.isActive()).thenReturn(true);
        when(firstRoom.getStatus()).thenReturn(RoomStatus.AVAILABLE);
        when(firstRoom.getRoomNumber()).thenReturn("101");
        com.example.hotel.entity.room.RoomType firstRoomType = capacityTwo();
        when(firstRoom.getRoomType()).thenReturn(firstRoomType);
        reservation.addRoom(new ReservationRoom(reservation, firstRoom, checkIn, checkOut, firstNightlyRate));

        Room secondRoom = mock(Room.class);
        UUID secondRoomId = UUID.randomUUID();
        when(secondRoom.getId()).thenReturn(secondRoomId);
        when(secondRoom.isActive()).thenReturn(true);
        when(secondRoom.getStatus()).thenReturn(RoomStatus.AVAILABLE);
        when(secondRoom.getRoomNumber()).thenReturn("202");
        com.example.hotel.entity.room.RoomType secondRoomType = capacityTwo();
        when(secondRoom.getRoomType()).thenReturn(secondRoomType);
        reservation.addRoom(new ReservationRoom(reservation, secondRoom, checkIn, checkOut, secondNightlyRate));

        reservation.calculateTotal();
        reservation.confirm();

        List<UUID> roomIds = List.of(firstRoomId, secondRoomId).stream().sorted().toList();
        List<Room> rooms = new ArrayList<>(List.of(firstRoom, secondRoom));

        List<Room> lockedRoomsInIdOrder =
                rooms.stream().sorted(java.util.Comparator.comparing(Room::getId)).toList();

        setCurrentUser(userId);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.findRoomIdsByReservationId(reservationId)).thenAnswer(invocation -> Optional.of(reservation).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));
        when(stayRepository.existsByReservationId(reservationId)).thenReturn(false);
        when(roomRepository.lockAllByIdIn(roomIds)).thenReturn(lockedRoomsInIdOrder);
        when(stayRepository.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(any(), eq(PaymentStatus.PAID)))
                .thenReturn(BigDecimal.ZERO);

        ReservationService reservationService = new ReservationService(
                reservationRepository,
                guestRepository,
                roomRepository,
                stayRepository,
                stayRoomAssignmentRepository,
                chargeRepository,
                auditLogRepository,
                new ReservationMapper(),
                reservationNumberGenerator,
                stayBalanceService, mock(com.example.hotel.service.room.RoomAvailabilityService.class), mock(com.example.hotel.service.booking.PrepaymentService.class),
                Clock.systemDefaultZone());

        return new Fixture(
                reservationService, reservation, reservationId, chargeRepository, stayRepository, rooms);
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

    /** Groups the collaborators and identifiers assembled for one check-in test scenario. */
    private record Fixture(
            ReservationService reservationService,
            Reservation reservation,
            UUID reservationId,
            ChargeRepository chargeRepository,
            StayRepository stayRepository,
            List<Room> rooms) {}

    private static com.example.hotel.entity.room.RoomType capacityTwo() {
        com.example.hotel.entity.room.RoomType type = org.mockito.Mockito.mock(com.example.hotel.entity.room.RoomType.class);
        org.mockito.Mockito.when(type.getCapacity()).thenReturn(2);
        return type;
    }
}
