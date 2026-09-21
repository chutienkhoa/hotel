package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.RoomReassignmentCandidateResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.exception.RoomReassignmentException.Reason;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Verifies pre-check-in Room reassignment: what changes, what must not, eligibility, state guards and audit. */
class ReservationRoomReassignmentServiceTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 21);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 24);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final RoomAvailabilityService availability = new RoomAvailabilityService(rooms, reservations);
    private final ReservationRoomReassignmentService service =
            new ReservationRoomReassignmentService(reservations, stays, rooms, availability, audits);
    private final UUID actor = UUID.randomUUID();
    private final Guest guest = Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);

    private Reservation reservation;
    private Room oldRoom;
    private Room otherRoom;
    private Room target;

    /** Builds a CONFIRMED OTA reservation with two rooms (101 old, 102 other) and an AVAILABLE target 203. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(actor, "staff"), null, List.of()));
        oldRoom = room("101", RoomStatus.OUT_OF_ORDER, true);
        otherRoom = room("102", RoomStatus.AVAILABLE, true);
        target = room("203", RoomStatus.AVAILABLE, true);
        reservation = new Reservation(UUID.randomUUID(), "R-1", guest, CHECK_IN, CHECK_OUT,
                BookingSource.BOOKING_COM, "BK-99", "VND", "keep me");
        reservation.addRoom(new ReservationRoom(reservation, oldRoom, CHECK_IN, CHECK_OUT, new BigDecimal("1000000")));
        reservation.addRoom(new ReservationRoom(reservation, otherRoom, CHECK_IN, CHECK_OUT, new BigDecimal("1500000")));
        reservation.calculateTotal();
        reservation.confirm();
        wire();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms one line is replaced while identity, guest, source, OTA reference, dates, rate and total are kept. */
    @Test
    void shouldReplaceOneRoomAndPreserveEverythingElse() {
        BigDecimal totalBefore = reservation.getTotalAmount();

        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        assertSame(target, reservation.findRoomLine(target.getId()).getRoom());
        assertEquals(null, reservation.findRoomLine(oldRoom.getId()));
        assertEquals(UUID.fromString(reservation.getId().toString()), reservation.getId());
        assertEquals("R-1", reservation.getReservationNumber());
        assertSame(guest, reservation.getGuest());
        assertEquals(BookingSource.BOOKING_COM, reservation.getSource());
        assertEquals("BK-99", reservation.getOtaBookingReference());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals("keep me", reservation.getNotes());
        assertEquals(CHECK_IN, reservation.getCheckInDate());
        assertEquals(CHECK_OUT, reservation.getCheckOutDate());
        ReservationRoom line = reservation.findRoomLine(target.getId());
        assertEquals(CHECK_IN, line.getCheckInDate());
        assertEquals(CHECK_OUT, line.getCheckOutDate());
        assertEquals(0, new BigDecimal("1000000").compareTo(line.getNightlyRate()));
        assertEquals(0, new BigDecimal("3000000").compareTo(line.getTotalAmount()));
        assertEquals(0, totalBefore.compareTo(reservation.getTotalAmount()));
    }

    /** Confirms the other line of a multi-room reservation is untouched. */
    @Test
    void shouldLeaveOtherRoomsUnchanged() {
        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        ReservationRoom other = reservation.findRoomLine(otherRoom.getId());
        assertSame(otherRoom, other.getRoom());
        assertEquals(0, new BigDecimal("1500000").compareTo(other.getNightlyRate()));
        assertEquals(2, reservation.getRooms().size());
    }

    /** Confirms the old room's status is not modified, whatever it was (no guest ever occupied it). */
    @ParameterizedTest
    @EnumSource(RoomStatus.class)
    void shouldNotChangeTheOldRoomStatus(RoomStatus oldStatus) {
        ReflectionTestUtils.setField(oldRoom, "status", oldStatus);

        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        assertEquals(oldStatus, oldRoom.getStatus());
        assertEquals(RoomStatus.AVAILABLE, target.getStatus());
    }

    /** Confirms an audit entry named REASSIGN_ROOM (not CHANGE_ROOM) records reservation, old room and new room. */
    @Test
    void shouldAuditReassignmentDistinctFromChangeRoom() {
        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals("REASSIGN_ROOM", ReflectionTestUtils.getField(log, "action"));
        assertEquals(reservation.getId(), ReflectionTestUtils.getField(log, "entityId"));
        assertEquals("Room 101", ReflectionTestUtils.getField(log, "oldValue"));
        assertEquals("Room 203", ReflectionTestUtils.getField(log, "newValue"));
        assertEquals(actor, ReflectionTestUtils.getField(log, "userId"));
    }

    /** Confirms only active AVAILABLE, conflict-free rooms not on this reservation are offered. */
    @Test
    void shouldOfferOnlyCheckInReadyConflictFreeRooms() {
        Room occupied = room("301", RoomStatus.OCCUPIED, true);
        Room dirty = room("302", RoomStatus.DIRTY, true);
        Room cleaning = room("303", RoomStatus.CLEANING, true);
        Room maintenance = room("304", RoomStatus.MAINTENANCE, true);
        Room outOfOrder = room("305", RoomStatus.OUT_OF_ORDER, true);
        Room conflicting = room("306", RoomStatus.AVAILABLE, true);
        when(reservations.hasOverlap(conflicting.getId(), CHECK_IN, CHECK_OUT, RoomAvailabilityService.BLOCKING_STATUSES))
                .thenReturn(true);
        when(rooms.findActiveWithRoomType()).thenReturn(List.of(oldRoom, otherRoom, target, occupied, dirty, cleaning,
                maintenance, outOfOrder, conflicting));

        List<String> offered = service.form(reservation.getId(), oldRoom.getId()).candidates().stream()
                .map(RoomReassignmentCandidateResponse::roomNumber).toList();

        assertEquals(List.of("203"), offered);
    }

    /** Confirms a target that is not active AVAILABLE is rejected and nothing changes. */
    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"OCCUPIED", "DIRTY", "CLEANING", "MAINTENANCE", "OUT_OF_ORDER"})
    void shouldRejectTargetThatIsNotAvailable(RoomStatus status) {
        ReflectionTestUtils.setField(target, "status", status);

        assertUnavailable();
    }

    /** Confirms an inactive target is rejected. */
    @Test
    void shouldRejectInactiveTarget() {
        ReflectionTestUtils.setField(target, "active", false);

        assertUnavailable();
    }

    /** Confirms an overlapping blocking reservation on the target is rejected. */
    @Test
    void shouldRejectOverlappingTarget() {
        when(reservations.hasOverlap(target.getId(), CHECK_IN, CHECK_OUT, RoomAvailabilityService.BLOCKING_STATUSES))
                .thenReturn(true);

        assertUnavailable();
    }

    /** Confirms the half-open interval: the overlap check is asked for exactly the booked [in, out) dates. */
    @Test
    void shouldCheckOverlapForTheBookedHalfOpenPeriod() {
        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        verify(reservations).hasOverlap(target.getId(), CHECK_IN, CHECK_OUT, RoomAvailabilityService.BLOCKING_STATUSES);
    }

    /** Confirms every non-CONFIRMED reservation state is rejected. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT"})
    void shouldRejectReservationsThatAreNotConfirmed(ReservationStatus status) {
        ReflectionTestUtils.setField(reservation, "status", status);

        RoomReassignmentException exception = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), target.getId()));

        assertEquals(Reason.RESERVATION_STATE_CHANGED, exception.getReason());
        assertUnchanged();
    }

    /** Confirms an existing Stay blocks the reassignment. */
    @Test
    void shouldRejectWhenAStayAlreadyExists() {
        when(stays.existsByReservationId(reservation.getId())).thenReturn(true);

        RoomReassignmentException exception = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), target.getId()));

        assertEquals(Reason.STAY_ALREADY_EXISTS, exception.getReason());
        assertUnchanged();
    }

    /** Confirms a stale UI (the line no longer holds the expected room) is rejected. */
    @Test
    void shouldRejectStaleCurrentRoom() {
        Room stale = room("999", RoomStatus.AVAILABLE, true);
        org.mockito.Mockito.doReturn(List.of(stale, target)).when(rooms).lockAllByIdIn(any());

        RoomReassignmentException exception = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), stale.getId(), target.getId()));

        assertEquals(Reason.ASSIGNMENT_CHANGED, exception.getReason());
        assertUnchanged();
    }

    /** Confirms a replacement already on the reservation (a concurrent reassignment got there first) is rejected. */
    @Test
    void shouldRejectTargetAlreadyAssignedToTheReservation() {
        org.mockito.Mockito.doReturn(List.of(oldRoom, otherRoom)).when(rooms).lockAllByIdIn(any());

        RoomReassignmentException exception = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), otherRoom.getId()));

        assertEquals(Reason.ROOM_ALREADY_ASSIGNED, exception.getReason());
        assertUnchanged();
    }

    /** Confirms replacing a room with itself, or a missing target, changes nothing. */
    @Test
    void shouldRejectSameRoomAndMissingRoom() {
        assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), oldRoom.getId()));
        org.mockito.Mockito.doReturn(List.of(oldRoom)).when(rooms).lockAllByIdIn(any());
        assertThrows(ResponseStatusException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), UUID.randomUUID()));
        assertUnchanged();
    }

    /** Confirms the Reservation is locked after the Rooms (check-in's order) and validation reads the locked row. */
    @Test
    void shouldLockRoomsBeforeTheReservation() {
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rooms, reservations);

        service.reassign(reservation.getId(), oldRoom.getId(), target.getId());

        order.verify(rooms).lockAllByIdIn(any());
        order.verify(reservations).findByIdForUpdate(reservation.getId());
    }

    private void assertUnavailable() {
        RoomReassignmentException exception = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation.getId(), oldRoom.getId(), target.getId()));

        assertEquals(Reason.ROOM_UNAVAILABLE, exception.getReason());
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertSame(oldRoom, reservation.findRoomLine(oldRoom.getId()).getRoom());
        assertEquals(null, reservation.findRoomLine(target.getId()));
        assertSame(otherRoom, reservation.findRoomLine(otherRoom.getId()).getRoom());
        verify(audits, never()).save(any());
    }

    private void wire() {
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(stays.existsByReservationId(reservation.getId())).thenReturn(false);
        when(rooms.lockAllByIdIn(any())).thenAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            List<Room> found = new ArrayList<>();
            for (Room candidate : List.of(oldRoom, otherRoom, target)) {
                if (ids.contains(candidate.getId())) {
                    found.add(candidate);
                }
            }
            return found;
        });
        when(reservations.hasOverlap(any(), any(), any(), any())).thenReturn(false);
    }

    private Room room(String number, RoomStatus status, boolean active) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn("Single");
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        ReflectionTestUtils.setField(room, "status", status);
        ReflectionTestUtils.setField(room, "active", active);
        return room;
    }
}
