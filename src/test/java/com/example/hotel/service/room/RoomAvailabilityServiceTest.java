package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.room.RoomRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies booking availability (a period, independent of current RoomStatus) is kept separate from check-in
 * readiness (right now, AVAILABLE only).
 */
class RoomAvailabilityServiceTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);
    private static final List<ReservationStatus> BLOCKING = List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final RoomAvailabilityService service = new RoomAvailabilityService(rooms, reservations);

    private Room room(String number, RoomStatus status, boolean active) {
        Room room = Room.create(UUID.randomUUID(), number, null, "1");
        ReflectionTestUtils.setField(room, "status", status);
        ReflectionTestUtils.setField(room, "active", active);
        return room;
    }

    private List<String> numbers(List<RoomLookupResponse> result) {
        return result.stream().map(RoomLookupResponse::roomNumber).toList();
    }

    /** Confirms a future booking may select an OCCUPIED room when the requested period does not overlap. */
    @Test
    void shouldOfferOccupiedRoomForNonOverlappingFutureBooking() {
        Room occupied = room("101", RoomStatus.OCCUPIED, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(occupied));
        when(reservations.hasOverlap(occupied.getId(), IN, OUT, BLOCKING)).thenReturn(false);

        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms a future booking may select DIRTY and CLEANING rooms when the requested period does not overlap. */
    @Test
    void shouldOfferDirtyAndCleaningRoomsForNonOverlappingFutureBooking() {
        Room dirty = room("102", RoomStatus.DIRTY, true);
        Room cleaning = room("103", RoomStatus.CLEANING, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(cleaning, dirty));
        when(reservations.hasOverlap(any(), any(), any(), any())).thenReturn(false);

        assertEquals(List.of("102", "103"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms a conflicting reservation excludes the room from booking availability, whatever its status. */
    @Test
    void shouldExcludeRoomWithConflictingReservationFromBooking() {
        Room available = room("101", RoomStatus.AVAILABLE, true);
        Room conflicting = room("102", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(available, conflicting));
        when(reservations.hasOverlap(conflicting.getId(), IN, OUT, BLOCKING)).thenReturn(true);

        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms the overlap check uses the requested half-open dates and only CONFIRMED / CHECKED_IN statuses. */
    @Test
    void shouldDelegateDateBoundariesAndBlockingStatusesToTheExistingOverlapCheck() {
        Room room = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(room));

        service.bookableRoomsForPeriod(IN, OUT);

        verify(reservations).hasOverlap(room.getId(), IN, OUT, BLOCKING);
        assertEquals(BLOCKING, RoomAvailabilityService.BLOCKING_STATUSES);
    }

    /** Confirms inactive, MAINTENANCE and OUT_OF_ORDER rooms are not offered for booking. */
    @Test
    void shouldNotOfferInactiveMaintenanceOrOutOfOrderRoomsForBooking() {
        when(rooms.findByActiveTrue()).thenReturn(List.of(
                room("101", RoomStatus.MAINTENANCE, true),
                room("102", RoomStatus.OUT_OF_ORDER, true),
                room("103", RoomStatus.AVAILABLE, false),
                room("104", RoomStatus.AVAILABLE, true)));
        when(reservations.hasOverlap(any(), any(), any(), any())).thenReturn(false);

        assertEquals(List.of("104"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms immediate check-in offers an AVAILABLE room with no conflict. */
    @Test
    void shouldOfferAvailableRoomWithoutConflictForImmediateCheckIn() {
        Room available = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(available));
        when(reservations.hasOverlap(any(), any(), any(), any())).thenReturn(false);

        assertEquals(List.of("101"), numbers(service.checkInReadyRoomsForPeriod(IN, OUT)));
    }

    /** Confirms immediate check-in offers no DIRTY, CLEANING, MAINTENANCE, OUT_OF_ORDER or OCCUPIED room. */
    @Test
    void shouldNotOfferNonReadyRoomsForImmediateCheckIn() {
        when(rooms.findByActiveTrue()).thenReturn(List.of(
                room("101", RoomStatus.DIRTY, true),
                room("102", RoomStatus.CLEANING, true),
                room("103", RoomStatus.MAINTENANCE, true),
                room("104", RoomStatus.OUT_OF_ORDER, true),
                room("105", RoomStatus.OCCUPIED, true),
                room("106", RoomStatus.AVAILABLE, false),
                room("107", RoomStatus.AVAILABLE, true)));
        when(reservations.hasOverlap(any(), any(), any(), any())).thenReturn(false);

        assertEquals(List.of("107"), numbers(service.checkInReadyRoomsForPeriod(IN, OUT)));
    }

    /** Confirms an AVAILABLE room with an overlapping reservation is not check-in-ready either. */
    @Test
    void shouldNotOfferAvailableRoomWithConflictForImmediateCheckIn() {
        Room room = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(room));
        when(reservations.hasOverlap(room.getId(), IN, OUT, BLOCKING)).thenReturn(true);

        assertEquals(List.of(), numbers(service.checkInReadyRoomsForPeriod(IN, OUT)));
    }
}
