package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.customer.Guest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the adult-capacity rule at the Reservation lifecycle boundaries: permissive DRAFT, hard-blocking Confirm and
 * Check-in (each re-evaluating the current rooms), with no partial mutation on failure.
 */
class ReservationCapacityLifecycleTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final ReservationNumberGenerator numbers = mock(ReservationNumberGenerator.class);
    private final com.example.hotel.service.room.RoomAvailabilityService roomAvailability =
            mock(com.example.hotel.service.room.RoomAvailabilityService.class);
    private final ReservationService service = new ReservationService(
            reservations, guests, rooms, stays, mock(StayRoomAssignmentRepository.class), charges, audits,
            new ReservationMapper(), numbers, mock(StayBalanceService.class), roomAvailability,
            Clock.fixed(IN.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final Guest primary = Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);

    /** Authenticates and stubs the repositories every test needs. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "manager"), null, List.of()));
        when(guests.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(numbers.generate()).thenReturn("R20261010-000001");
        when(stays.save(any(Stay.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(charges.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- DRAFT

    /** Confirms creating and editing a DRAFT may exceed capacity (3 adults in a DOUBLE). */
    @Test
    void shouldAllowDraftsToExceedCapacity() {
        Room doubleRoom = room("101", 2);
        when(rooms.findAllById(any())).thenReturn(List.of(doubleRoom));
        CreateRequest request = new CreateRequest(primary.getId(), IN, OUT, 3, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(doubleRoom.getId(), BigDecimal.TEN)), List.of());

        service.create(request);
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null);
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.updateDraft(draft.getId(), request);

        assertEquals(3, draft.getAdultCount());
        assertEquals(ReservationStatus.DRAFT, draft.getStatus());
    }

    // -------------------------------------------------------------- CONFIRM

    /** Confirms exact capacity and greater capacity confirm; children never consume capacity. */
    @Test
    void shouldConfirmWhenAdultCapacityIsSufficient() {
        assertConfirms(draft(2, 3, room("101", 2)));
        assertConfirms(draft(1, 0, room("102", 2)));
        assertConfirms(draft(2, 5, room("103", 2)));
    }

    /** Confirms capacity is summed across rooms, accompanying profiles never count, and per-room fit is not required. */
    @Test
    void shouldSumCapacityAcrossRoomsAndIgnoreAccompanyingProfiles() {
        Reservation multi = draft(3, 0, room("101", 2), room("102", 1));
        multi.replaceAccompanyingGuests(List.of(guest("G-2"), guest("G-3"), guest("G-4"), guest("G-5"), guest("G-6")), UUID.randomUUID());
        assertConfirms(multi);

        Reservation zeroProfiles = draft(3, 0, room("103", 2), room("104", 1));
        assertConfirms(zeroProfiles);
    }

    /** Confirms insufficient capacity blocks confirmation with 409, without partial mutation. */
    @Test
    void shouldRejectConfirmWhenCapacityIsInsufficient() {
        Reservation reservation = draft(3, 0, room("101", 2));
        arrange(reservation, false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.confirm(reservation.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("3 adults") && exception.getReason().contains("only 2"));
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
        assertEquals(3, reservation.getAdultCount());
        assertEquals(1, reservation.getRooms().size());
        verify(audits, never()).save(any());
    }

    /** Confirms adults + accompanying profiles are not summed: 4 adults on capacity 3 stays insufficient. */
    @Test
    void shouldRejectFourAdultsOnCapacityThreeRegardlessOfProfiles() {
        Reservation reservation = draft(4, 0, room("101", 2), room("102", 1));
        reservation.replaceAccompanyingGuests(List.of(guest("G-2")), UUID.randomUUID());
        arrange(reservation, false);

        assertThrows(ResponseStatusException.class, () -> service.confirm(reservation.getId()));
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
    }

    /** Confirms a room whose RoomType capacity is null blocks confirmation with a clear reason. */
    @Test
    void shouldRejectConfirmWhenCapacityIsNotConfigured() {
        Reservation reservation = draft(1, 0, room("101", 2), room("102", null));
        arrange(reservation, false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.confirm(reservation.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Room capacity is not configured for room type"));
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
    }

    /** Confirms overlap validation still runs and still wins for an overlapping period. */
    @Test
    void shouldKeepOverlapValidationOnConfirm() {
        Reservation reservation = draft(1, 0, room("101", 2));
        arrange(reservation, true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.confirm(reservation.getId()));

        assertEquals("Room is already booked for these dates", exception.getReason());
        assertEquals(ReservationStatus.DRAFT, reservation.getStatus());
    }

    // -------------------------------------------------------------- CHECK-IN

    /** Confirms check-in succeeds at exact capacity (children do not count) and creates the Stay and ROOM charges. */
    @Test
    void shouldCheckInWhenCapacityIsSufficient() {
        Reservation reservation = confirmed(2, 2, room("101", 2));
        arrangeCheckIn(reservation);

        service.checkIn(reservation.getId());

        verify(stays).save(any(Stay.class));
        verify(charges).save(any(Charge.class));
        assertEquals(ReservationStatus.CHECKED_IN, reservation.getStatus());
    }

    /** Confirms check-in re-evaluates capacity (does not trust confirmation) and creates nothing when it fails. */
    @Test
    void shouldBlockCheckInWhenCurrentCapacityIsInsufficient() {
        Room doubleRoom = room("101", 2);
        Reservation reservation = confirmed(2, 0, doubleRoom);
        // Capacity later changes below the adults: the reservation was valid when confirmed.
        when(doubleRoom.getRoomType().getCapacity()).thenReturn(1);
        arrangeCheckIn(reservation);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.checkIn(reservation.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(RoomStatus.AVAILABLE, doubleRoom.getStatus());
        verify(stays, never()).save(any());
        verify(charges, never()).save(any());
    }

    /** Confirms check-in is blocked when a RoomType capacity later becomes null. */
    @Test
    void shouldBlockCheckInWhenCapacityBecomesNotConfigured() {
        Room doubleRoom = room("101", 2);
        Reservation reservation = confirmed(1, 0, doubleRoom);
        when(doubleRoom.getRoomType().getCapacity()).thenReturn(null);
        arrangeCheckIn(reservation);

        assertThrows(ResponseStatusException.class, () -> service.checkIn(reservation.getId()));

        assertEquals(RoomStatus.AVAILABLE, doubleRoom.getStatus());
        verify(stays, never()).save(any());
        verify(charges, never()).save(any());
    }

    /** Confirms existing room-state checks stay authoritative and win over the capacity check. */
    @Test
    void shouldKeepRoomStateChecksAuthoritative() {
        Room dirty = room("101", 1);
        Reservation reservation = confirmed(3, 0, dirty);
        org.springframework.test.util.ReflectionTestUtils.setField(dirty, "status", RoomStatus.DIRTY);
        arrangeCheckIn(reservation);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.checkIn(reservation.getId()));

        assertEquals("Room is not available for check-in", exception.getReason());
    }

    /** Confirms check-in consumes the composition set by the dedicated update and still enforces the shared rule. */
    @Test
    void shouldLetCheckInUseTheCountSetByTheCompositionUpdate() {
        Reservation reservation = confirmed(2, 0, room("101", 2), room("102", 1));
        arrangeCheckIn(reservation);
        List<Object[]> rows = reservation.getRooms().stream()
                .map(line -> new Object[] {reservation.getId(), line.getRoom()}).toList();
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findBookedRoomsByReservationIdIn(List.of(reservation.getId()))).thenReturn(rows);

        service.updateConfirmedGuestComposition(reservation.getId(),
                new com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest(3, 0, List.of()));
        assertEquals(3, reservation.getAdultCount());
        service.checkIn(reservation.getId());

        assertEquals(ReservationStatus.CHECKED_IN, reservation.getStatus());
        verify(stays).save(any(Stay.class));
    }

    /** Confirms once check-in has happened the dedicated update is refused (composition frozen). */
    @Test
    void shouldFreezeCompositionAfterCheckIn() {
        Reservation reservation = confirmed(2, 0, room("101", 2));
        arrangeCheckIn(reservation);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        service.checkIn(reservation.getId());

        assertThrows(com.example.hotel.exception.GuestCompositionUpdateException.class, () -> service.updateConfirmedGuestComposition(
                reservation.getId(), new com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest(1, 0, List.of())));
        assertEquals(2, reservation.getAdultCount());
    }

    // ------------------------------------------------------------- helpers

    private void assertConfirms(Reservation reservation) {
        arrange(reservation, false);
        assertEquals("CONFIRMED", service.confirm(reservation.getId()).status());
    }

    private void arrange(Reservation reservation, boolean overlap) {
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        List<Room> roomList = reservation.getRooms().stream().map(ReservationRoom::getRoom).toList();
        when(rooms.lockAllByIdIn(any())).thenReturn(roomList);
        when(roomAvailability.conflictedRoomIds(any(), any(), any()))
                .thenReturn(overlap ? java.util.Set.of(roomList.get(0).getId()) : java.util.Set.of());
    }

    private void arrangeCheckIn(Reservation reservation) {
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(stays.existsByReservationId(reservation.getId())).thenReturn(false);
        List<Room> roomList = reservation.getRooms().stream().map(ReservationRoom::getRoom).toList();
        when(rooms.lockAllByIdIn(any())).thenReturn(roomList);
    }

    private Reservation confirmed(int adults, int children, Room... roomArray) {
        Reservation reservation = draft(adults, children, roomArray);
        reservation.confirm();
        return reservation;
    }

    private Reservation draft(int adults, int children, Room... roomArray) {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, adults, children,
                BookingSource.DIRECT, null, "VND", null);
        for (Room room : roomArray) {
            reservation.addRoom(new ReservationRoom(reservation, room, IN, OUT, new BigDecimal("1000000")));
        }
        reservation.calculateTotal();
        return reservation;
    }

    private static Room room(String number, Integer capacity) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn("T" + number);
        when(type.getCapacity()).thenReturn(capacity);
        return Room.create(UUID.randomUUID(), number, type, "1");
    }

    private static Guest guest(String code) {
        return Guest.create(UUID.randomUUID(), code, "Bo", "Tran", null, null, "Vietnam", null, null);
    }
}
