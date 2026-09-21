package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.response.HousekeepingRoomResponse;
import com.example.hotel.dto.room.response.HousekeepingWorkspaceResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.RoomNextArrivalRow;
import com.example.hotel.repository.room.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Verifies Housekeeping grouping, READY derivation, next-arrival labelling and derived priority ordering. */
class HousekeepingQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final Clock clock = Clock.fixed(TODAY.atTime(23, 30).atZone(ZONE).toInstant(), ZONE);
    private final HousekeepingQueryService service = new HousekeepingQueryService(rooms, reservations, clock);
    private final List<Room> roomList = new ArrayList<>();
    private final List<RoomNextArrivalRow> arrivals = new ArrayList<>();

    /** Confirms each status lands in its group, and OCCUPIED rooms appear nowhere. */
    @Test
    void shouldGroupRoomsByHousekeepingStatus() {
        room("101", r -> { r.occupy(); r.markDirty(); });
        room("102", r -> { r.occupy(); r.markDirty(); r.startCleaning(); });
        room("103", r -> { });
        room("104", Room::startMaintenance);
        room("105", Room::markOutOfOrder);
        room("106", Room::occupy);

        HousekeepingWorkspaceResponse workspace = load();

        assertEquals(List.of("101"), numbers(workspace.needsCleaning()));
        assertEquals(List.of("102"), numbers(workspace.cleaning()));
        assertEquals(List.of("103"), numbers(workspace.ready()));
        assertEquals(List.of("104", "105"), numbers(workspace.issues()));
        assertEquals("MAINTENANCE", workspace.issues().get(0).status());
        assertEquals("OUT_OF_ORDER", workspace.issues().get(1).status());
    }

    /** Confirms READY is only active AVAILABLE: a DIRTY room and an inactive room are never ready. */
    @Test
    void shouldNotTreatNonAvailableOrInactiveRoomsAsReady() {
        room("201", r -> { r.occupy(); r.markDirty(); });
        Room inactive = room("202", r -> { });
        when(rooms.findActiveWithRoomType()).thenReturn(roomList.stream().filter(r -> r != inactive).toList());

        HousekeepingWorkspaceResponse workspace = service.loadWorkspace();

        assertTrue(workspace.ready().isEmpty());
        assertEquals(List.of("201"), numbers(workspace.needsCleaning()));
    }

    /** Confirms a checkout-created DIRTY room and a Room-Change-created DIRTY room both appear in Needs Cleaning. */
    @Test
    void shouldListCheckoutAndRoomChangeDirtiedRoomsInNeedsCleaning() {
        room("301", r -> { r.occupy(); r.markDirty(); });
        room("302", r -> { r.occupy(); r.releaseForRoomChange(); });

        assertEquals(List.of("301", "302"), numbers(load().needsCleaning()));
    }

    /** Confirms a DIRTY room with an arrival today is urgent and first; then nearest arrival; then no arrival. */
    @Test
    void shouldOrderDirtyRoomsByDerivedPriority() {
        Room none = dirty("401");
        Room later = dirty("402");
        Room today = dirty("403");
        Room tomorrow = dirty("404");
        Room alsoNone = dirty("400");
        arrival(later, TODAY.plusDays(4));
        arrival(today, TODAY);
        arrival(tomorrow, TODAY.plusDays(1));

        HousekeepingWorkspaceResponse workspace = load();

        assertEquals(List.of("403", "404", "402", "400", "401"), numbers(workspace.needsCleaning()));
        assertTrue(workspace.needsCleaning().get(0).urgent());
        assertFalse(workspace.needsCleaning().get(1).urgent());
        assertEquals("TODAY", workspace.needsCleaning().get(0).arrivalKind());
        assertEquals("TOMORROW", workspace.needsCleaning().get(1).arrivalKind());
        assertEquals("DATE", workspace.needsCleaning().get(2).arrivalKind());
        assertEquals(TODAY.plusDays(4), workspace.needsCleaning().get(2).nextArrivalDate());
        assertEquals("NONE", workspace.needsCleaning().get(3).arrivalKind());
        assertNull(workspace.needsCleaning().get(3).nextArrivalDate());
        assertEquals(none.getRoomNumber(), "401");
        assertEquals(alsoNone.getRoomNumber(), "400");
    }

    /** Confirms urgency is derived only for DIRTY rooms: a CLEANING room with an arrival today is not urgent. */
    @Test
    void shouldOnlyMarkDirtyRoomsUrgent() {
        Room cleaning = room("501", r -> { r.occupy(); r.markDirty(); r.startCleaning(); });
        arrival(cleaning, TODAY);

        assertFalse(load().cleaning().get(0).urgent());
        assertEquals("TODAY", load().cleaning().get(0).arrivalKind());
    }

    /** Confirms arrival labels follow the hotel date from the injected clock, not the system time. */
    @Test
    void shouldUseHotelDateFromTheClockForArrivalLabels() {
        Room room = room("601", r -> { });
        arrival(room, TODAY.plusDays(1));

        HousekeepingWorkspaceResponse workspace = load();

        assertEquals(TODAY, workspace.hotelDate());
        assertEquals("TOMORROW", workspace.ready().get(0).arrivalKind());
    }

    /** Confirms only CONFIRMED counts as an upcoming arrival, so CANCELLED, NO_SHOW and CHECKED_OUT never do. */
    @Test
    void shouldQueryOnlyConfirmedReservationsAsUpcomingArrivals() {
        assertEquals(List.of(ReservationStatus.CONFIRMED), HousekeepingQueryService.UPCOMING_ARRIVAL_STATUSES);
        when(rooms.findActiveWithRoomType()).thenReturn(List.of());
        when(reservations.findNextArrivalsFrom(TODAY, HousekeepingQueryService.UPCOMING_ARRIVAL_STATUSES))
                .thenReturn(List.of());

        assertTrue(service.loadWorkspace().needsCleaning().isEmpty());
    }

    private Room dirty(String number) {
        return room(number, r -> { r.occupy(); r.markDirty(); });
    }

    private Room room(String number, Consumer<Room> setup) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn("Single");
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        setup.accept(room);
        roomList.add(room);
        return room;
    }

    private void arrival(Room room, LocalDate date) {
        arrivals.add(new RoomNextArrivalRow(room.getId(), date));
    }

    private HousekeepingWorkspaceResponse load() {
        when(rooms.findActiveWithRoomType()).thenReturn(roomList);
        when(reservations.findNextArrivalsFrom(TODAY, HousekeepingQueryService.UPCOMING_ARRIVAL_STATUSES))
                .thenReturn(arrivals);
        return service.loadWorkspace();
    }

    private static List<String> numbers(List<HousekeepingRoomResponse> rows) {
        return rows.stream().map(HousekeepingRoomResponse::roomNumber).toList();
    }
}
