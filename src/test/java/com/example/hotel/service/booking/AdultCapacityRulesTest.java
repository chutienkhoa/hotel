package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies the shared adult-capacity rule: adults against summed RoomType capacity, aggregated across rooms. */
class AdultCapacityRulesTest {

    /** Confirms valid and insufficient outcomes for single and multi-room sets (children never take part). */
    @ParameterizedTest
    @CsvSource({
        "1, 1, VALID, 1", "2, 2, VALID, 2", "1, 2, VALID, 2",
        "3, 2, INSUFFICIENT_ADULT_CAPACITY, 2",
        "3, '2 1', VALID, 3", "4, '2 1', INSUFFICIENT_ADULT_CAPACITY, 3",
        "3, '2 1', VALID, 3", "3, '1 1', INSUFFICIENT_ADULT_CAPACITY, 2", "2, '1 1', VALID, 2",
        "4, '2 2', VALID, 4", "5, '2 2', INSUFFICIENT_ADULT_CAPACITY, 4"
    })
    void shouldSumAdultCapacityAcrossAllAssignedRooms(int adults, String capacities, AdultCapacityRules.Outcome outcome, int total) {
        List<Room> rooms = java.util.Arrays.stream(capacities.split(" ")).map(c -> room("T", Integer.valueOf(c))).toList();

        AdultCapacityRules.Result result = AdultCapacityRules.evaluate(adults, rooms);

        assertEquals(outcome, result.outcome());
        assertEquals(total, result.totalAdultCapacity());
        assertEquals(adults, result.adultCount());
        assertEquals(outcome == AdultCapacityRules.Outcome.VALID, result.valid());
    }

    /** Confirms capacity is aggregated, not validated per room: 3 adults fit DOUBLE(2)+SINGLE(1) with no assignment. */
    @Test
    void shouldNotValidatePerRoom() {
        assertTrue(AdultCapacityRules.evaluate(3, List.of(room("DOUBLE", 2), room("SINGLE", 1))).valid());
    }

    /**
     * Confirms the rule's only inputs are the adult count and the rooms: children, party size and the number of known
     * accompanying profiles are not parameters, so they cannot influence the outcome.
     */
    @Test
    void shouldOnlyDependOnAdultsAndRooms() throws Exception {
        assertEquals(2, AdultCapacityRules.class.getMethod("evaluate", int.class, java.util.Collection.class).getParameterCount());
        // 2 adults + any number of children/profiles on a DOUBLE is the same VALID result.
        assertTrue(AdultCapacityRules.evaluate(2, List.of(room("DOUBLE", 2))).valid());
        // 3 adults on a DOUBLE stays INSUFFICIENT no matter how many accompanying profiles or children exist.
        assertEquals(AdultCapacityRules.Outcome.INSUFFICIENT_ADULT_CAPACITY,
                AdultCapacityRules.evaluate(3, List.of(room("DOUBLE", 2))).outcome());
    }

    /** Confirms a RoomType with null capacity is a configuration error, never zero, unlimited or skipped. */
    @Test
    void shouldTreatNullCapacityAsNotConfigured() {
        AdultCapacityRules.Result alone = AdultCapacityRules.evaluate(1, List.of(room("MYSTERY", null)));
        AdultCapacityRules.Result withOthers = AdultCapacityRules.evaluate(1, List.of(room("DOUBLE", 2), room("MYSTERY", null)));

        assertEquals(AdultCapacityRules.Outcome.CAPACITY_NOT_CONFIGURED, alone.outcome());
        assertNull(alone.totalAdultCapacity());
        assertEquals(List.of("MYSTERY"), alone.unconfiguredRoomTypes());
        // Not skipped: enough configured capacity elsewhere still cannot succeed.
        assertEquals(AdultCapacityRules.Outcome.CAPACITY_NOT_CONFIGURED, withOthers.outcome());
        // Not unlimited: a huge party is reported as not configured, not valid.
        assertEquals(AdultCapacityRules.Outcome.CAPACITY_NOT_CONFIGURED,
                AdultCapacityRules.evaluate(99, List.of(room("MYSTERY", null))).outcome());
        // Not zero: 1 adult would be INSUFFICIENT under a zero reading, but the outcome is NOT_CONFIGURED instead.
        assertTrue(alone.outcome() != AdultCapacityRules.Outcome.INSUFFICIENT_ADULT_CAPACITY);
    }

    /** Confirms a Room without any RoomType is also a configuration error, and no rooms cannot host adults. */
    @Test
    void shouldHandleMissingRoomTypeAndEmptyRoomSet() {
        Room noType = Room.create(UUID.randomUUID(), "101", null, "1");

        assertEquals(AdultCapacityRules.Outcome.CAPACITY_NOT_CONFIGURED, AdultCapacityRules.evaluate(1, List.of(noType)).outcome());
        assertEquals(AdultCapacityRules.Outcome.INSUFFICIENT_ADULT_CAPACITY, AdultCapacityRules.evaluate(1, List.of()).outcome());
    }

    private static Room room(String typeName, Integer capacity) {
        RoomType type = mock(RoomType.class);
        when(type.getName()).thenReturn(typeName);
        when(type.getCapacity()).thenReturn(capacity);
        return Room.create(UUID.randomUUID(), "R", type, "1");
    }
}
