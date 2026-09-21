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
        ArrivalReadiness readiness = evaluate(TODAY, false, true, room("101", RoomStatus.AVAILABLE, true));

        assertEquals(ArrivalReadinessState.READY, readiness.state());
        assertTrue(readiness.blockers().isEmpty());
        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101")), readiness.issues());
    }

    /** Confirms every non-AVAILABLE room status maps to its specific blocker. */
    @ParameterizedTest
    @CsvSource({
        "DIRTY,ROOM_DIRTY", "CLEANING,ROOM_CLEANING", "OCCUPIED,ROOM_OCCUPIED",
        "MAINTENANCE,ROOM_MAINTENANCE", "OUT_OF_ORDER,ROOM_OUT_OF_ORDER"
    })
    void shouldMapRoomStatusToBlocker(RoomStatus status, ArrivalIssueCode expected) {
        ArrivalReadiness readiness = evaluate(TODAY, false, true, room("102", status, true));

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        assertEquals(List.of(new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, expected, "102")),
                readiness.blockers());
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
        ArrivalReadiness readiness = evaluate(TODAY, false, true,
                room("101", RoomStatus.AVAILABLE, true), room("102", RoomStatus.DIRTY, true));

        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, readiness.state());
        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "102")), readiness.blockers());
        assertTrue(readiness.issues().contains(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101")));
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
                false, List.of(room("101", RoomStatus.AVAILABLE, true)), true);
        ArrivalReadiness normal = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY, TODAY,
                false, List.of(room("101", RoomStatus.AVAILABLE, true)), true);

        assertEquals(CheckInTiming.EARLY, early.timing());
        assertEquals(ArrivalIssueCode.ARRIVAL_TOO_EARLY, early.blockers().get(0).code());
        assertEquals(CheckInTiming.NORMAL, normal.timing());
        assertEquals(ArrivalReadinessState.READY, normal.state());
    }

    /** Confirms a past-due CONFIRMED arrival stays visible as an explicit overdue WARNING and does not block. */
    @Test
    void shouldRepresentPastDueArrivalAsWarningWithoutBlocking() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.minusDays(2),
                TODAY, false, List.of(room("101", RoomStatus.AVAILABLE, true)), true);

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
                List.of(room("101", RoomStatus.DIRTY, true)), false);
        ArrivalReadiness stay = evaluate(TODAY, true, true, room("101", RoomStatus.AVAILABLE, true));

        assertEquals(List.of(new ArrivalReadinessIssue(
                ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.RESERVATION_NOT_CONFIRMED, null)), draft.issues());
        assertEquals(ArrivalIssueCode.STAY_ALREADY_EXISTS, stay.blockers().get(0).code());
    }

    /** Confirms issues are ordered BLOCKER, then WARNING, then INFO. */
    @Test
    void shouldOrderIssuesBySeverity() {
        ArrivalReadiness readiness = ArrivalReadinessRules.evaluate(ReservationStatus.CONFIRMED, TODAY.minusDays(1),
                TODAY, false, List.of(room("101", RoomStatus.AVAILABLE, true), room("102", RoomStatus.CLEANING, true)),
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

    private static ArrivalReadiness evaluate(LocalDate checkIn, boolean stayExists, boolean passport, Room... rooms) {
        return ArrivalReadinessRules.evaluate(
                ReservationStatus.CONFIRMED, checkIn, TODAY, stayExists, List.of(rooms), passport);
    }

    private static Room room(String number, RoomStatus status, boolean active) {
        Room room = Room.create(UUID.randomUUID(), number, mock(RoomType.class), "1");
        ReflectionTestUtils.setField(room, "status", status);
        ReflectionTestUtils.setField(room, "active", active);
        return room;
    }
}
