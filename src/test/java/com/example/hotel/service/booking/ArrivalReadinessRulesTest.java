package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies the derived Arrival Readiness rules: room-state mapping, timing, multi-room, warnings and severities. */
class ArrivalReadinessRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    /** Confirms an active AVAILABLE room and no other blocker is READY, with an INFO ready line for the room. */
    @Test
    void shouldBeReadyForActiveAvailableRoom() {
        Room room = room("101", RoomStatus.AVAILABLE, true);
        ArrivalReadiness readiness = evaluate(TODAY, false, true, room);

        assertEquals(ArrivalReadinessState.READY, readiness.state());
        assertTrue(readiness.blockers().isEmpty());
        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101", room.getId())), readiness.issues());
    }

    /** Confirms every non-AVAILABLE room status maps to its specific blocker. */
    @ParameterizedTest
    @CsvSource({
        "DIRTY,ROOM_DIRTY", "CLEANING,ROOM_CLEANING", "OCCUPIED,ROOM_OCCUPIED",
        "MAINTENANCE,ROOM_MAINTENANCE", "OUT_OF_ORDER,ROOM_OUT_OF_ORDER"
    })
    void shouldMapRoomStatusToBlocker(RoomStatus status, ArrivalIssueCode expected) {
        Room room = room("102", status, true);
        ArrivalReadiness readiness = evaluate(TODAY, false, true, room);

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        assertEquals(List.of(new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, expected, "102", room.getId())),
                readiness.blockers());
        assertTrue(readiness.blockers().get(0).roomBlocker());
    }

    /** Confirms an inactive room blocks, even if its status is AVAILABLE or DIRTY. */
    @ParameterizedTest
    @CsvSource({"AVAILABLE", "DIRTY"})
    void shouldBlockInactiveRoom(RoomStatus status) {
        ArrivalReadiness readiness = evaluate(TODAY, false, true, room("103", status, false));

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        assertEquals(ArrivalIssueCode.ROOM_INACTIVE, readiness.blockers().get(0).code());
        assertEquals("103", readiness.blockers().get(0).roomNumber());
    }

    /** Confirms one bad room in a multi-room reservation blocks overall and names the affected room only. */
    @Test
    void shouldIdentifyTheAffectedRoomInMultiRoomReservation() {
        Room ok = room("101", RoomStatus.AVAILABLE, true);
        Room dirty = room("102", RoomStatus.DIRTY, true);
        ArrivalReadiness readiness = evaluate(TODAY, false, true, ok, dirty);

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "102", dirty.getId())), readiness.blockers());
        assertTrue(readiness.issues().contains(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101", ok.getId())));
    }

    /** Confirms all rooms ready gives overall READY. */
    @Test
    void shouldBeReadyWhenAllRoomsAreReady() {
        ArrivalReadiness readiness = evaluate(TODAY, false, true,
                room("101", RoomStatus.AVAILABLE, true), room("102", RoomStatus.AVAILABLE, true));

        assertEquals(ArrivalReadinessState.READY, readiness.state());
    }

    /** Confirms the early-arrival rule: a future check-in date is a blocker, today and past are not. */
    @Test
    void shouldBlockEarlyArrivalOnly() {
        ArrivalReadiness early = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.plusDays(1), TODAY,
                false, 1, List.of(room("101", RoomStatus.AVAILABLE, true)), true);
        ArrivalReadiness normal = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY, TODAY,
                false, 1, List.of(room("101", RoomStatus.AVAILABLE, true)), true);

        assertEquals(CheckInTiming.EARLY, early.timing());
        assertEquals(ArrivalIssueCode.ARRIVAL_TOO_EARLY, early.blockers().get(0).code());
        assertEquals(CheckInTiming.NORMAL, normal.timing());
        assertEquals(ArrivalReadinessState.READY, normal.state());
    }

    /** Confirms a past-due CONFIRMED arrival stays visible as an explicit overdue WARNING and does not block. */
    @Test
    void shouldRepresentPastDueArrivalAsWarningWithoutBlocking() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.minusDays(2),
                TODAY, false, 1, List.of(room("101", RoomStatus.AVAILABLE, true)), true);

        assertEquals(CheckInTiming.LATE, readiness.timing());
        assertEquals(ArrivalReadinessState.READY, readiness.state());
        assertTrue(readiness.issues().contains(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.WARNING, ArrivalIssueCode.ARRIVAL_OVERDUE, null)));
    }

    /** Confirms a missing passport is only a WARNING and never a blocker (passport is optional in V1). */
    @Test
    void shouldNotBlockOnMissingPassport() {
        ArrivalReadiness readiness = evaluate(TODAY, false, false, room("101", RoomStatus.AVAILABLE, true));

        assertEquals(ArrivalReadinessState.READY, readiness.state());
        assertTrue(readiness.issues().contains(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.WARNING, ArrivalIssueCode.PASSPORT_MISSING, null)));
    }

    /** Confirms a non-CONFIRMED reservation reports only its state blocker, and an existing Stay blocks. */
    @Test
    void shouldBlockNonConfirmedReservationAndExistingStay() {
        ArrivalReadiness draft = ArrivalReadinessRules.evaluate(ReservationStatus.DRAFT, TODAY, TODAY, false,
                 1, List.of(room("101", RoomStatus.DIRTY, true)), false);
        ArrivalReadiness stay = evaluate(TODAY, true, true, room("101", RoomStatus.AVAILABLE, true));

        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.RESERVATION_NOT_CONFIRMED, null)), draft.issues());
        assertEquals(ArrivalIssueCode.STAY_ALREADY_EXISTS, stay.blockers().get(0).code());
    }

    /** Confirms issues are ordered BLOCKER, then WARNING, then INFO. */
    @Test
    void shouldOrderIssuesBySeverity() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.minusDays(1),
                TODAY, false, 1, List.of(room("101", RoomStatus.AVAILABLE, true), room("102", RoomStatus.CLEANING, true)),
                false);

        List<ArrivalIssueSeverity> severities = readiness.issues().stream().map(ArrivalReadinessIssue::severity).toList();
        assertEquals(List.of(ArrivalIssueSeverity.BLOCKER, ArrivalIssueSeverity.WARNING, ArrivalIssueSeverity.WARNING,
                ArrivalIssueSeverity.INFO), severities);
    }

    /** Confirms the room blocker is exactly the negation of the Room check-in-readiness rule for every state. */
    @Test
    void shouldAgreeWithRoomCheckInReadinessForEveryRoomState() {
        for (RoomStatus status : RoomStatus.values()) {
            for (boolean active : new boolean[] {true, false}) {
                Room room = room("1", status, active);
                assertEquals(room.isReadyForCheckIn(), ArrivalReadinessRules.roomBlocker(room).isEmpty(),
                        status + " active=" + active);
            }
        }
        assertFalse(ArrivalReadinessRules.roomBlocker(room("1", RoomStatus.AVAILABLE, true)).isPresent());
    }

    /** Confirms sufficient capacity adds no blocker, and children/profiles are never inputs (only adults are). */
    @Test
    void shouldNotAddCapacityBlockerWhenAdultsFit() {
        Room doubleRoom = room("101", RoomStatus.AVAILABLE, true);

        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                ReservationStatus.CONFIRMED, TODAY, TODAY, false, 2, List.of(doubleRoom), true);

        assertEquals(ArrivalReadinessState.READY, readiness.state());
        assertTrue(readiness.issues().stream().noneMatch(issue -> issue.code() == ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY
                || issue.code() == ArrivalIssueCode.CAPACITY_NOT_CONFIGURED));
    }

    /** Confirms insufficient capacity is a BLOCKER carrying adults and capacity for its message. */
    @Test
    void shouldBlockOnInsufficientAdultCapacity() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                ReservationStatus.CONFIRMED, TODAY, TODAY, false, 3, List.of(room("101", RoomStatus.AVAILABLE, true)), true);

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        ArrivalReadinessIssue issue = readiness.blockers().get(0);
        assertEquals(ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY, issue.code());
        assertEquals(ArrivalIssueSeverity.BLOCKER, issue.severity());
        assertEquals(3, issue.adultCount());
        assertEquals(2, issue.totalAdultCapacity());
        assertEquals(3, issue.messageArgument(0));
        assertEquals(2, issue.messageArgument(1));
    }

    /** Confirms a RoomType without capacity is a BLOCKER naming the type. */
    @Test
    void shouldBlockOnCapacityNotConfigured() {
        RoomType unconfigured = mock(RoomType.class);
        org.mockito.Mockito.when(unconfigured.getName()).thenReturn("Suite");
        org.mockito.Mockito.when(unconfigured.getCapacity()).thenReturn(null);
        Room room = Room.create(UUID.randomUUID(), "901", unconfigured, "9");

        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(
                ReservationStatus.CONFIRMED, TODAY, TODAY, false, 1, List.of(room), true);

        assertEquals(ArrivalIssueCode.CAPACITY_NOT_CONFIGURED, readiness.blockers().get(0).code());
        assertEquals("Suite", readiness.blockers().get(0).messageArgument(0));
    }

    /** Confirms capacity coexists with every existing blocker and the passport/overdue warnings, unchanged. */
    @Test
    void shouldKeepExistingBlockersAndWarningsAlongsideCapacity() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.minusDays(1), TODAY,
                false, 3, List.of(room("101", RoomStatus.DIRTY, true)), false);

        List<ArrivalIssueCode> blockers = readiness.blockers().stream().map(ArrivalReadinessIssue::code).toList();
        assertEquals(List.of(ArrivalIssueCode.ROOM_DIRTY, ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY), blockers);
        assertTrue(readiness.issues().stream().anyMatch(i -> i.code() == ArrivalIssueCode.ARRIVAL_OVERDUE
                && i.severity() == ArrivalIssueSeverity.WARNING));
        assertTrue(readiness.issues().stream().anyMatch(i -> i.code() == ArrivalIssueCode.PASSPORT_MISSING
                && i.severity() == ArrivalIssueSeverity.WARNING));
    }

    /** Confirms a multi-room reservation is evaluated on the summed capacity (DOUBLE + SINGLE for 3 adults). */
    @Test
    void shouldEvaluateSummedCapacityForMultiRoomReadiness() {
        RoomType single = mock(RoomType.class);
        org.mockito.Mockito.when(single.getCapacity()).thenReturn(1);
        Room singleRoom = Room.create(UUID.randomUUID(), "102", single, "1");

        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY, TODAY, false, 3,
                List.of(room("101", RoomStatus.AVAILABLE, true), singleRoom), true);

        assertEquals(ArrivalReadinessState.READY, readiness.state());
    }

    private static ArrivalReadiness evaluate(LocalDate checkIn, boolean stayExists, boolean passport, Room... rooms) {
        return ArrivalReadinessRules.evaluate(
                ReservationStatus.CONFIRMED, checkIn, TODAY, stayExists, 1, List.of(rooms), passport);
    }

    private static Room room(String number, RoomStatus status, boolean active) {
        RoomType type = mock(RoomType.class);
        org.mockito.Mockito.when(type.getCapacity()).thenReturn(2);
        Room room = Room.create(UUID.randomUUID(), number, type, "1");
        ReflectionTestUtils.setField(room, "status", status);
        ReflectionTestUtils.setField(room, "active", active);
        return room;
    }
}
