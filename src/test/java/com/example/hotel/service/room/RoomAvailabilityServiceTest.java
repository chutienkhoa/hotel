package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
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
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final java.util.Set<UUID> conflicting = new java.util.HashSet<>();
    private final RoomAvailabilityService service = new RoomAvailabilityService(
            rooms, java.time.Clock.fixed(LocalDate.of(2026, 10, 1).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    {
        when(rooms.findRoomIdsWithInventoryConflict(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<UUID> asked = invocation.getArgument(0);
                    return asked.stream().filter(conflicting::contains).toList();
                });
    }

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

        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms a future booking may select DIRTY and CLEANING rooms when the requested period does not overlap. */
    @Test
    void shouldOfferDirtyAndCleaningRoomsForNonOverlappingFutureBooking() {
        Room dirty = room("102", RoomStatus.DIRTY, true);
        Room cleaning = room("103", RoomStatus.CLEANING, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(cleaning, dirty));

        assertEquals(List.of("102", "103"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms a conflicting reservation excludes the room from booking availability, whatever its status. */
    @Test
    void shouldExcludeRoomWithConflictingReservationFromBooking() {
        Room available = room("101", RoomStatus.AVAILABLE, true);
        Room clash = room("102", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(available, clash));
        conflicting.add(clash.getId());

        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms one bulk query is asked for the requested half-open dates converted to hotel-zone instants. */
    @Test
    void shouldAskOneBulkQueryWithHotelZoneBoundaries() {
        Room a = room("101", RoomStatus.AVAILABLE, true);
        Room b = room("102", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(a, b));

        service.bookableRoomsForPeriod(IN, OUT);

        verify(rooms, org.mockito.Mockito.times(1)).findRoomIdsWithInventoryConflict(
                eq(List.of(a.getId(), b.getId())), eq(IN), eq(OUT),
                eq(OUT.atStartOfDay(ZONE).toInstant()), eq(IN.plusDays(1).atStartOfDay(ZONE).toInstant()),
                eq(false), eq(ReservationStatus.CONFIRMED), eq(com.example.hotel.entity.booking.StayStatus.CHECKED_IN), any());
    }

    /** Confirms the current night is protected only once the requested interval has started (today >= in). */
    @Test
    void shouldFlagCurrentNightProtectionOnlyWhenHotelTodayIsNotBeforeCheckIn() {
        Room a = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(a));

        service.bookableRoomsForPeriod(LocalDate.of(2026, 10, 1), OUT);

        verify(rooms).findRoomIdsWithInventoryConflict(
                any(), any(), any(), any(), any(), eq(true), any(), any(), any());
    }

    /** Confirms no query is issued for an empty room list. */
    @Test
    void shouldSkipQueryForEmptyRoomList() {
        assertEquals(java.util.Set.of(), service.conflictedRoomIds(List.of(), IN, OUT));
        org.mockito.Mockito.verifyNoInteractions(rooms);
    }

    /** Confirms inactive, MAINTENANCE and OUT_OF_ORDER rooms are not offered for booking. */
    @Test
    void shouldNotOfferInactiveMaintenanceOrOutOfOrderRoomsForBooking() {
        when(rooms.findByActiveTrue()).thenReturn(List.of(
                room("101", RoomStatus.MAINTENANCE, true),
                room("102", RoomStatus.OUT_OF_ORDER, true),
                room("103", RoomStatus.AVAILABLE, false),
                room("104", RoomStatus.AVAILABLE, true)));

        assertEquals(List.of("104"), numbers(service.bookableRoomsForPeriod(IN, OUT)));
    }

    /** Confirms immediate check-in offers an AVAILABLE room with no conflict. */
    @Test
    void shouldOfferAvailableRoomWithoutConflictForImmediateCheckIn() {
        Room available = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(available));

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

        assertEquals(List.of("107"), numbers(service.checkInReadyRoomsForPeriod(IN, OUT)));
    }

    /** Confirms an AVAILABLE room with an overlapping reservation is not check-in-ready either. */
    @Test
    void shouldNotOfferAvailableRoomWithConflictForImmediateCheckIn() {
        Room room = room("101", RoomStatus.AVAILABLE, true);
        when(rooms.findByActiveTrue()).thenReturn(List.of(room));
        conflicting.add(room.getId());

        assertEquals(List.of(), numbers(service.checkInReadyRoomsForPeriod(IN, OUT)));
    }

    /** Confirms normal callers exclude no Stay, and only an explicit Stay identifier is passed through. */
    @Test
    void shouldExcludeNoStayByDefaultAndOnlyTheGivenStayWhenAsked() {
        UUID roomId = UUID.randomUUID();
        UUID stayId = UUID.randomUUID();

        service.conflictedRoomIds(List.of(roomId), IN, OUT);
        service.conflictedRoomIds(List.of(roomId), IN, OUT, stayId);

        verify(rooms).findRoomIdsWithInventoryConflict(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), eq(new UUID(0L, 0L)));
        verify(rooms).findRoomIdsWithInventoryConflict(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), eq(stayId));
    }
}
