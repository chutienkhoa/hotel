package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomInventoryOrigin;
import com.example.hotel.entity.room.RoomInventoryPeriod;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.entity.room.RoomUnavailableReason;
import com.example.hotel.repository.room.RoomInventoryPeriodRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Verifies Room inventory history splits only on RoomType or sellability changes and never repairs silently. */
class RoomInventoryHistoryServiceTest {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant T0 = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T08:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final RoomInventoryPeriodRepository periods = mock(RoomInventoryPeriodRepository.class);
    private final RoomInventoryHistoryService service =
            new RoomInventoryHistoryService(periods, Clock.fixed(T1, HOTEL_ZONE));

    /** Confirms a new Room gets one open RECORDED period with its RoomType and no unavailable reason. */
    @Test
    void shouldInitializeOpenRecordedSellablePeriod() {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);

        service.initialize(room, T0, USER);

        RoomInventoryPeriod created = savedPeriods(1).get(0);
        assertEquals(type, created.getRoomType());
        assertEquals(room, created.getRoom());
        assertNull(created.getUnavailableReason());
        assertTrue(created.isSellable());
        assertEquals(RoomInventoryOrigin.RECORDED, created.getOrigin());
        assertEquals(T0, created.getEffectiveFrom());
        assertNull(created.getEffectiveTo());
        assertEquals(USER, created.getCreatedBy());
    }

    /**
     * Confirms every sellability-changing transition closes the old period and opens the new one at one Instant.
     *
     * @param status target status that removes the Room from sellable inventory
     */
    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"MAINTENANCE", "OUT_OF_ORDER"})
    void shouldSplitWhenRoomBecomesUnavailableAndWhenRestored(RoomStatus status) {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);
        RoomInventoryPeriod sellable = open(room, type, null, T0);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(sellable));
        ReflectionTestUtils.setField(room, "status", status);

        service.sync(room, T1, USER);

        RoomInventoryPeriod unavailable = savedPeriods(1).get(0);
        assertEquals(T1, sellable.getEffectiveTo());
        assertEquals(sellable.getEffectiveTo(), unavailable.getEffectiveFrom());
        assertEquals(RoomUnavailableReason.valueOf(status.name()), unavailable.getUnavailableReason());
        assertFalse(unavailable.isSellable());
        assertTrue(unavailable.isOpen());
        assertEquals(RoomInventoryOrigin.RECORDED, unavailable.getOrigin());

        RoomInventoryPeriod restoredFrom = unavailable;
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(restoredFrom));
        ReflectionTestUtils.setField(room, "status", RoomStatus.AVAILABLE);
        Instant t2 = T1.plusSeconds(3600);

        service.sync(room, t2, USER);

        ArgumentCaptor<RoomInventoryPeriod> saved = ArgumentCaptor.forClass(RoomInventoryPeriod.class);
        verify(periods, org.mockito.Mockito.times(2)).save(saved.capture());
        RoomInventoryPeriod restored = saved.getAllValues().get(1);
        assertEquals(t2, unavailable.getEffectiveTo());
        assertEquals(unavailable.getEffectiveTo(), restored.getEffectiveFrom());
        assertNull(restored.getUnavailableReason());
    }

    /**
     * Confirms status changes inside sellable inventory create no history.
     *
     * @param status sellable target status
     */
    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"AVAILABLE", "OCCUPIED", "DIRTY", "CLEANING"})
    void shouldNotSplitForSellableToSellableStatusChanges(RoomStatus status) {
        RoomType type = type();
        Room room = room(type, status);
        RoomInventoryPeriod sellable = open(room, type, null, T0);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(sellable));

        service.sync(room, T1, USER);

        verify(periods, never()).save(any());
        verify(periods, never()).saveAndFlush(any());
        assertTrue(sellable.isOpen());
    }

    /** Confirms a RoomType change splits the period and keeps the sellable state. */
    @Test
    void shouldSplitWhenRoomTypeChanges() {
        RoomType original = type();
        RoomType changed = type();
        Room room = room(original, RoomStatus.AVAILABLE);
        RoomInventoryPeriod sellable = open(room, original, null, T0);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(sellable));
        room.updateProfile("101", changed, "1");

        service.sync(room, T1, USER);

        RoomInventoryPeriod next = savedPeriods(1).get(0);
        assertEquals(changed, next.getRoomType());
        assertNull(next.getUnavailableReason());
        assertEquals(T1, sellable.getEffectiveTo());
        assertEquals(sellable.getEffectiveTo(), next.getEffectiveFrom());
    }

    /** Confirms a RoomType change while the Room is unavailable preserves the unavailable reason. */
    @Test
    void shouldPreserveUnavailableReasonWhenRoomTypeChanges() {
        RoomType original = type();
        RoomType changed = type();
        Room room = room(original, RoomStatus.OUT_OF_ORDER);
        RoomInventoryPeriod unavailable = open(room, original, RoomUnavailableReason.OUT_OF_ORDER, T0);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(unavailable));
        room.updateProfile("101", changed, "1");

        service.sync(room, T1, USER);

        RoomInventoryPeriod next = savedPeriods(1).get(0);
        assertEquals(changed, next.getRoomType());
        assertEquals(RoomUnavailableReason.OUT_OF_ORDER, next.getUnavailableReason());
    }

    /** Confirms an unchanged RoomType (even a different instance with the same id) and profile edits do not split. */
    @Test
    void shouldNotSplitForRoomNumberFloorOrUnchangedRoomType() {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);
        RoomInventoryPeriod sellable = open(room, type, null, T0);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(sellable));
        UUID sameTypeId = type.getId();
        RoomType sameTypeOtherInstance = mock(RoomType.class);
        when(sameTypeOtherInstance.getId()).thenReturn(sameTypeId);
        room.updateProfile("999", sameTypeOtherInstance, "9");

        service.sync(room, T1, USER);

        verify(periods, never()).save(any());
        assertTrue(sellable.isOpen());
    }

    /** Confirms a Room without an open period fails loudly instead of being repaired. */
    @Test
    void shouldFailWhenRoomHasNoOpenPeriod() {
        Room room = room(type(), RoomStatus.AVAILABLE);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.sync(room, T1, USER));

        verify(periods, never()).save(any());
    }

    /** Confirms more than one open period is treated as corruption, not silently resolved. */
    @Test
    void shouldFailWhenRoomHasMoreThanOneOpenPeriod() {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);
        when(periods.findOpenByRoomId(room.getId()))
                .thenReturn(List.of(open(room, type, null, T0), open(room, type, null, T0.plusSeconds(1))));

        assertThrows(IllegalStateException.class, () -> service.sync(room, T1, USER));

        verify(periods, never()).save(any());
    }

    /** Confirms a change that is not after the open period start is rejected rather than producing an empty period. */
    @Test
    void shouldRejectChangeAtOrBeforeOpenPeriodStart() {
        RoomType type = type();
        Room room = room(type, RoomStatus.OUT_OF_ORDER);
        when(periods.findOpenByRoomId(room.getId())).thenReturn(List.of(open(room, type, null, T1)));

        assertThrows(IllegalStateException.class, () -> service.sync(room, T1, USER));

        verify(periods, never()).save(any());
    }

    /** Confirms several transitions on one hotel date keep their real Instants and form a contiguous chain. */
    @Test
    void shouldPreserveActualInstantsForMultipleTransitionsOnOneDate() {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);
        Instant created = Instant.parse("2026-09-10T01:00:00Z");
        Instant outOfOrder = Instant.parse("2026-09-10T08:00:00Z");
        Instant restored = Instant.parse("2026-09-10T09:30:00Z");
        Instant maintenance = Instant.parse("2026-09-10T11:15:00Z");
        List<RoomInventoryPeriod> chain = new ArrayList<>();
        chain.add(open(room, type, null, created));
        when(periods.findOpenByRoomId(room.getId())).thenAnswer(invocation -> List.of(chain.get(chain.size() - 1)));
        when(periods.save(any(RoomInventoryPeriod.class))).thenAnswer(invocation -> {
            chain.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        ReflectionTestUtils.setField(room, "status", RoomStatus.OUT_OF_ORDER);
        service.sync(room, outOfOrder, USER);
        ReflectionTestUtils.setField(room, "status", RoomStatus.AVAILABLE);
        service.sync(room, restored, USER);
        ReflectionTestUtils.setField(room, "status", RoomStatus.MAINTENANCE);
        service.sync(room, maintenance, USER);

        assertEquals(4, chain.size());
        assertEquals(created, chain.get(0).getEffectiveFrom());
        assertEquals(outOfOrder, chain.get(0).getEffectiveTo());
        assertEquals(outOfOrder, chain.get(1).getEffectiveFrom());
        assertEquals(restored, chain.get(1).getEffectiveTo());
        assertEquals(restored, chain.get(2).getEffectiveFrom());
        assertEquals(maintenance, chain.get(2).getEffectiveTo());
        assertEquals(maintenance, chain.get(3).getEffectiveFrom());
        assertNull(chain.get(3).getEffectiveTo());
        LocalDate date = LocalDate.of(2026, 9, 10);
        assertEquals(date, LocalDate.ofInstant(chain.get(1).getEffectiveFrom(), HOTEL_ZONE));
        assertEquals(date, LocalDate.ofInstant(chain.get(1).getEffectiveTo(), HOTEL_ZONE));
    }

    /** Confirms closing a system-created BOOTSTRAP period never invents a creator. */
    @Test
    void shouldNotRewriteMissingCreatorWhenClosingBootstrapPeriod() {
        RoomType type = type();
        Room room = room(type, RoomStatus.AVAILABLE);
        RoomInventoryPeriod bootstrap = open(room, type, null, T0);
        ReflectionTestUtils.setField(bootstrap, "createdBy", null);
        ReflectionTestUtils.setField(bootstrap, "origin", RoomInventoryOrigin.BOOTSTRAP);

        bootstrap.close(T1, USER);

        assertNull(bootstrap.getCreatedBy());
        assertEquals(USER, bootstrap.getUpdatedBy());
    }

    /** Confirms the writing operations require the caller's Room transaction. */
    @Test
    void shouldRequireExistingTransactionForWrites() throws NoSuchMethodException {
        Transactional initialize = RoomInventoryHistoryService.class
                .getMethod("initialize", Room.class, Instant.class, UUID.class)
                .getAnnotation(Transactional.class);
        Transactional sync = RoomInventoryHistoryService.class
                .getMethod("sync", Room.class, Instant.class, UUID.class)
                .getAnnotation(Transactional.class);

        assertNotNull(initialize);
        assertNotNull(sync);
        assertEquals(Propagation.MANDATORY, initialize.propagation());
        assertEquals(Propagation.MANDATORY, sync.propagation());
    }

    /** Confirms the history start is the hotel-zone date of the earliest BOOTSTRAP Instant. */
    @Test
    void shouldDeriveHistoryStartInHotelZone() {
        when(periods.findEarliestEffectiveFromByOrigin(RoomInventoryOrigin.BOOTSTRAP))
                .thenReturn(Instant.parse("2026-08-31T18:00:00Z"));

        assertEquals(Optional.of(LocalDate.of(2026, 9, 1)), service.bootstrapHistoryStart());
        assertEquals(Optional.of(YearMonth.of(2026, 9)), service.firstFullySupportedMonth());
    }

    /** Confirms a mid-month history start makes the following month the first supported month. */
    @Test
    void shouldStartSupportAtFollowingMonthWhenHistoryStartsMidMonth() {
        when(periods.findEarliestEffectiveFromByOrigin(RoomInventoryOrigin.BOOTSTRAP))
                .thenReturn(Instant.parse("2026-09-20T03:00:00Z"));

        assertEquals(Optional.of(YearMonth.of(2026, 10)), service.firstFullySupportedMonth());
        assertEquals(YearMonth.of(2027, 1), RoomInventoryHistoryService.firstFullySupportedMonth(LocalDate.of(2026, 12, 31)));
        assertEquals(YearMonth.of(2026, 9), RoomInventoryHistoryService.firstFullySupportedMonth(LocalDate.of(2026, 9, 1)));
    }

    /** Confirms no boundary is fabricated when the database has no BOOTSTRAP period. */
    @Test
    void shouldReportNoBoundaryWithoutBootstrapPeriods() {
        when(periods.findEarliestEffectiveFromByOrigin(RoomInventoryOrigin.BOOTSTRAP)).thenReturn(null);

        assertEquals(Optional.empty(), service.bootstrapHistoryStart());
        assertEquals(Optional.empty(), service.firstFullySupportedMonth());
    }

    private List<RoomInventoryPeriod> savedPeriods(int expected) {
        ArgumentCaptor<RoomInventoryPeriod> captor = ArgumentCaptor.forClass(RoomInventoryPeriod.class);
        verify(periods, org.mockito.Mockito.atLeast(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private RoomInventoryPeriod open(Room room, RoomType type, RoomUnavailableReason reason, Instant from) {
        return new RoomInventoryPeriod(room, type, reason, from, USER);
    }

    private Room room(RoomType type, RoomStatus status) {
        Room room = Room.create(UUID.randomUUID(), "101", type, "1");
        ReflectionTestUtils.setField(room, "status", status);
        return room;
    }

    private RoomType type() {
        RoomType type = mock(RoomType.class);
        when(type.getId()).thenReturn(UUID.randomUUID());
        return type;
    }
}
