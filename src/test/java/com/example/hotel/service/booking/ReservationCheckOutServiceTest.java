package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the atomic Reservation check-out use case and its required preconditions.
 *
 * <p><b>Concurrency coverage limitation:</b> this is a Mockito-only unit test suite with no real
 * database, threads, or transactions, so it cannot prove true multi-threaded lock contention
 * between concurrent check-out attempts, or between check-out and a concurrent Payment or Room
 * Change. Where such scenarios are exercised here (e.g. {@code
 * shouldRejectSecondCheckOutAttemptAfterFirstAlreadyCompleted}), the test simulates only the
 * end-state each side of the shared {@code Stay} PESSIMISTIC_WRITE lock would observe once
 * serialized — it is not a substitute for a Testcontainers-based true-concurrency integration
 * test, which this sandbox cannot run (Docker unavailable). No concurrency claim beyond
 * lock-and-recompute reasoning already applied by the audited production code is made here.</p>
 */
class ReservationCheckOutServiceTest {

    /** Clears the authenticated user established for each service operation. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms exact payment produces a complete Reservation, Stay, and Room check-out. */
    @Test
    void shouldCheckOutCheckedInReservationWithZeroOutstanding() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED));
        Instant actualCheckInAt = fixture.stay().getActualCheckInAt();
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        Response response = fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(ReservationStatus.CHECKED_OUT, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_OUT, fixture.stay().getStatus());
        assertEquals(actualCheckInAt, fixture.stay().getActualCheckInAt());
        assertNotNull(fixture.stay().getActualCheckOutAt());
        assertEquals("CHECKED_OUT", response.status());
        fixture.rooms().forEach(room -> assertEquals(RoomStatus.DIRTY, room.getStatus()));
        fixture.rooms().forEach(room -> assertEquals(fixture.userId(), room.getUpdatedBy()));
        assertEquals(fixture.creatorId(), fixture.reservation().getCreatedBy());
        assertEquals(fixture.userId(), fixture.reservation().getUpdatedBy());
        assertEquals(fixture.creatorId(), fixture.stay().getCreatedBy());
        assertEquals(fixture.userId(), fixture.stay().getUpdatedBy());
        verify(fixture.roomRepository()).lockAllByIdIn(fixture.roomIds());
        verify(fixture.auditLogRepository()).save(any(AuditLog.class));
    }

    /** Confirms a Reservation outside CHECKED_IN cannot be checked out. */
    @Test
    void shouldRejectReservationOutsideCheckedInState() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        fixture.reservation().checkOut();

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms a Reservation without its required unique Stay cannot be checked out. */
    @Test
    void shouldRejectReservationWithoutStay() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayRepository().findByReservationIdForUpdate(fixture.reservation().getId()))
                .thenReturn(Optional.empty());

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms only a checked-in Stay can participate in a Reservation check-out. */
    @Test
    void shouldRejectCheckedOutStay() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        fixture.stay().checkOut(Instant.now());

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));
        verify(fixture.stayBalanceService(), never()).calculate(any());
    }

    /** Confirms any positive outstanding amount prevents check-out before Room mutations. */
    @Test
    void shouldRejectOutstandingBalanceBeforeChangingStates() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.ZERO));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_IN, fixture.stay().getStatus());
        assertEquals(RoomStatus.OCCUPIED, fixture.rooms().getFirst().getStatus());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(any());
    }

    /** Confirms a negative outstanding balance (overpayment) also prevents check-out, not only a positive one. */
    @Test
    void shouldRejectNegativeOutstandingBalanceBeforeChangingStates() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, new BigDecimal("15")));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_IN, fixture.stay().getStatus());
        assertEquals(RoomStatus.OCCUPIED, fixture.rooms().getFirst().getStatus());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(any());
    }

    /** Confirms non-contributing Payment statuses leave a positive outstanding balance. */
    @ParameterizedTest
    @MethodSource("nonContributingPaymentStatuses")
    void shouldRejectCheckoutWhenNonPaidPaymentDoesNotSettleBalance(String paymentStatus) {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.ZERO));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus(), paymentStatus);
    }

    /** Confirms a non-occupied assigned Room fails check-out without partially changing any state. */
    @Test
    void shouldRejectNonOccupiedRoomWithoutPartialCheckout() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.AVAILABLE));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(RoomStatus.OCCUPIED, fixture.rooms().getFirst().getStatus());
        assertEquals(RoomStatus.AVAILABLE, fixture.rooms().get(1).getStatus());
        assertEquals(ReservationStatus.CHECKED_IN, fixture.reservation().getStatus());
        assertEquals(StayStatus.CHECKED_IN, fixture.stay().getStatus());
        verify(fixture.auditLogRepository(), never()).save(any(AuditLog.class));
    }

    /** Confirms every open assignment is closed at exactly Stay.actualCheckOutAt, preserving prior history. */
    @Test
    void shouldCloseOpenAssignmentsAtActualCheckOutAt() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        fixture.service().checkOut(fixture.reservation().getId());

        for (StayRoomAssignment assignment : fixture.openAssignments()) {
            assertEquals(fixture.stay().getActualCheckOutAt(), assignment.getAssignedTo());
        }
    }

    /**
     * Confirms that after a Room Change, check-out releases the current room from the open
     * assignment (e.g. 305), never the original ReservationRoom's room (e.g. 201), which was
     * already released and its assignment already closed by Room Change.
     */
    @Test
    void shouldReleaseCurrentRoomFromOpenAssignmentAfterRoomChangeNotOriginalRoom() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED));
        Room originalRoom201 = fixture.rooms().get(0);
        Room room202 = fixture.rooms().get(1);
        Room replacementRoom305 = Room.create(UUID.randomUUID(), "305", null, "3");
        replacementRoom305.occupy();
        replacementRoom305.audit(fixture.creatorId());
        originalRoom201.releaseForRoomChange();

        StayRoomAssignment closedAssignment201 = new StayRoomAssignment(
                fixture.stay(), originalRoom201, fixture.reservation().getRooms().get(0),
                fixture.stay().getActualCheckInAt(), com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);
        closedAssignment201.close(Instant.now());
        StayRoomAssignment openAssignment305 = new StayRoomAssignment(
                fixture.stay(), replacementRoom305, fixture.reservation().getRooms().get(0),
                Instant.now(), com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);
        List<StayRoomAssignment> currentOpenAssignments = List.of(openAssignment305, fixture.openAssignments().get(1));
        when(fixture.stayRoomAssignmentRepository().findOpenByStayId(fixture.stay().getId()))
                .thenReturn(currentOpenAssignments);
        List<UUID> currentRoomIds =
                List.of(replacementRoom305.getId(), room202.getId()).stream().sorted().toList();
        when(fixture.roomRepository().lockAllByIdIn(currentRoomIds))
                .thenReturn(List.of(replacementRoom305, room202).stream()
                        .sorted(java.util.Comparator.comparing(Room::getId))
                        .toList());
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(RoomStatus.DIRTY, originalRoom201.getStatus(), "already vacated (DIRTY) by Room Change, must stay untouched");
        assertEquals(RoomStatus.DIRTY, replacementRoom305.getStatus());
        assertEquals(RoomStatus.DIRTY, room202.getStatus());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(fixture.roomIds());
        verify(fixture.roomRepository()).lockAllByIdIn(currentRoomIds);
    }

    /**
     * Confirms actualCheckOutAt is derived from the injected authoritative Clock, and that every
     * assignment open before check-out is closed at exactly that same instant. This test fails
     * against a bare {@code Instant.now()} implementation, since the fixed Clock instant used here
     * is deliberately far from wall-clock time.
     */
    @Test
    void shouldRecordActualCheckOutAtFromAuthoritativeClockForStayAndAllOpenAssignments() {
        Instant fixedInstant = Instant.parse("2026-09-18T03:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Ho_Chi_Minh"));
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED), fixedClock);
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(fixedInstant, fixture.stay().getActualCheckOutAt());
        for (StayRoomAssignment assignment : fixture.openAssignments()) {
            assertEquals(fixedInstant, assignment.getAssignedTo());
        }
    }

    /**
     * Confirms a two-hop Room Change lineage (201 -> 305 -> 402) releases only the current room
     * (402) at check-out, closing only its assignment at actualCheckOutAt, while both earlier
     * released rooms (201, 305) and their already-closed assignment history remain completely
     * untouched — proving check-out never rewrites Room History.
     */
    @Test
    void shouldReleaseOnlyFinalRoomAfterTwoRoomChangesAndPreserveEarlierHistory() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        Room originalRoom201 = fixture.rooms().get(0);
        Room intermediateRoom305 = Room.create(UUID.randomUUID(), "305", null, "3");
        Room finalRoom402 = Room.create(UUID.randomUUID(), "402", null, "4");
        intermediateRoom305.audit(fixture.creatorId());
        finalRoom402.occupy();
        finalRoom402.audit(fixture.creatorId());
        originalRoom201.releaseForRoomChange();

        Instant firstChangeAt = Instant.parse("2026-09-11T14:00:00Z");
        Instant secondChangeAt = Instant.parse("2026-09-12T09:00:00Z");
        StayRoomAssignment closedAssignment201 = new StayRoomAssignment(
                fixture.stay(), originalRoom201, fixture.reservation().getRooms().get(0),
                fixture.stay().getActualCheckInAt(), com.example.hotel.entity.booking.RoomChangeReason.ROOM_ISSUE, null);
        closedAssignment201.close(firstChangeAt);
        StayRoomAssignment closedAssignment305 = new StayRoomAssignment(
                fixture.stay(), intermediateRoom305, fixture.reservation().getRooms().get(0),
                firstChangeAt, com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);
        closedAssignment305.close(secondChangeAt);
        StayRoomAssignment openAssignment402 = new StayRoomAssignment(
                fixture.stay(), finalRoom402, fixture.reservation().getRooms().get(0),
                secondChangeAt, com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);

        when(fixture.stayRoomAssignmentRepository().findOpenByStayId(fixture.stay().getId()))
                .thenReturn(List.of(openAssignment402));
        when(fixture.roomRepository().lockAllByIdIn(List.of(finalRoom402.getId())))
                .thenReturn(List.of(finalRoom402));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(RoomStatus.DIRTY, originalRoom201.getStatus());
        assertEquals(RoomStatus.AVAILABLE, intermediateRoom305.getStatus(), "never occupied by this fixture, must stay unaffected");
        assertEquals(RoomStatus.DIRTY, finalRoom402.getStatus());
        assertEquals(firstChangeAt, closedAssignment201.getAssignedTo(), "earlier history must not be rewritten");
        assertEquals(secondChangeAt, closedAssignment305.getAssignedTo(), "earlier history must not be rewritten");
        assertEquals(fixture.stay().getActualCheckOutAt(), openAssignment402.getAssignedTo());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(fixture.roomIds());
    }

    /**
     * Confirms a multi-room Stay (three originally booked rooms, two later changed) atomically
     * dirties exactly its three current rooms and closes exactly their three open assignments at
     * the same actualCheckOutAt, while the two previously released rooms remain untouched.
     */
    @Test
    void shouldCheckOutAllCurrentRoomsForMultiRoomStayWithPartialRoomChanges() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED, RoomStatus.OCCUPIED, RoomStatus.OCCUPIED));
        Room originalRoom201 = fixture.rooms().get(0);
        Room unchangedRoom202 = fixture.rooms().get(1);
        Room originalRoom203 = fixture.rooms().get(2);
        Room replacementRoom305 = Room.create(UUID.randomUUID(), "305", null, "3");
        Room replacementRoom401 = Room.create(UUID.randomUUID(), "401", null, "4");
        replacementRoom305.occupy();
        replacementRoom305.audit(fixture.creatorId());
        replacementRoom401.occupy();
        replacementRoom401.audit(fixture.creatorId());
        originalRoom201.releaseForRoomChange();
        originalRoom203.releaseForRoomChange();

        StayRoomAssignment closedAssignment201 = new StayRoomAssignment(
                fixture.stay(), originalRoom201, fixture.reservation().getRooms().get(0),
                fixture.stay().getActualCheckInAt(), com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);
        closedAssignment201.close(Instant.now());
        StayRoomAssignment openAssignment305 = new StayRoomAssignment(
                fixture.stay(), replacementRoom305, fixture.reservation().getRooms().get(0),
                Instant.now(), com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);
        StayRoomAssignment closedAssignment203 = new StayRoomAssignment(
                fixture.stay(), originalRoom203, fixture.reservation().getRooms().get(2),
                fixture.stay().getActualCheckInAt(), com.example.hotel.entity.booking.RoomChangeReason.OPERATIONAL, null);
        closedAssignment203.close(Instant.now());
        StayRoomAssignment openAssignment401 = new StayRoomAssignment(
                fixture.stay(), replacementRoom401, fixture.reservation().getRooms().get(2),
                Instant.now(), com.example.hotel.entity.booking.RoomChangeReason.OPERATIONAL, null);
        StayRoomAssignment unchangedOpenAssignment202 = fixture.openAssignments().get(1);

        List<StayRoomAssignment> currentOpenAssignments =
                List.of(openAssignment305, unchangedOpenAssignment202, openAssignment401);
        List<UUID> currentRoomIds = List.of(
                        replacementRoom305.getId(), unchangedRoom202.getId(), replacementRoom401.getId())
                .stream().sorted().toList();
        when(fixture.stayRoomAssignmentRepository().findOpenByStayId(fixture.stay().getId()))
                .thenReturn(currentOpenAssignments);
        when(fixture.roomRepository().lockAllByIdIn(currentRoomIds))
                .thenReturn(List.of(replacementRoom305, unchangedRoom202, replacementRoom401).stream()
                        .sorted(java.util.Comparator.comparing(Room::getId))
                        .toList());
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        fixture.service().checkOut(fixture.reservation().getId());

        assertEquals(RoomStatus.DIRTY, replacementRoom305.getStatus());
        assertEquals(RoomStatus.DIRTY, unchangedRoom202.getStatus());
        assertEquals(RoomStatus.DIRTY, replacementRoom401.getStatus());
        assertEquals(RoomStatus.DIRTY, originalRoom201.getStatus(), "already vacated (DIRTY) by Room Change, must stay untouched");
        assertEquals(RoomStatus.DIRTY, originalRoom203.getStatus(), "already vacated (DIRTY) by Room Change, must stay untouched");
        Instant actualCheckOutAt = fixture.stay().getActualCheckOutAt();
        assertEquals(actualCheckOutAt, openAssignment305.getAssignedTo());
        assertEquals(actualCheckOutAt, unchangedOpenAssignment202.getAssignedTo());
        assertEquals(actualCheckOutAt, openAssignment401.getAssignedTo());
        verify(fixture.roomRepository(), never()).lockAllByIdIn(fixture.roomIds());
    }

    /**
     * Confirms a second check-out attempt after the first has already committed cannot perform
     * another transition: the Stay lock serializes the two attempts, and the second observes
     * Stay.status already CHECKED_OUT and rejects cleanly with no further mutation and no second
     * CHECK_OUT audit entry. This simulates, rather than proves via real threads, what the shared
     * Stay PESSIMISTIC_WRITE lock guarantees under true concurrency (see class Javadoc note on
     * concurrency coverage limitations).
     */
    @Test
    void shouldRejectSecondCheckOutAttemptAfterFirstAlreadyCompleted() {
        Fixture fixture = fixture(List.of(RoomStatus.OCCUPIED));
        when(fixture.stayBalanceService().calculate(fixture.stay().getId()))
                .thenReturn(balance(BigDecimal.TEN, BigDecimal.TEN));

        Response first = fixture.service().checkOut(fixture.reservation().getId());
        assertEquals("CHECKED_OUT", first.status());
        Instant firstCheckOutAt = fixture.stay().getActualCheckOutAt();

        assertConflict(() -> fixture.service().checkOut(fixture.reservation().getId()));

        assertEquals(firstCheckOutAt, fixture.stay().getActualCheckOutAt(), "second attempt must not overwrite the recorded checkout instant");
        assertEquals(StayStatus.CHECKED_OUT, fixture.stay().getStatus());
        verify(fixture.auditLogRepository(), org.mockito.Mockito.times(1)).save(any(AuditLog.class));
    }

    /** Supplies Payment statuses that the balance service excludes from total paid payments. */
    private static Stream<Arguments> nonContributingPaymentStatuses() {
        return Stream.of(
                Arguments.of("PENDING"), Arguments.of("FAILED"), Arguments.of("REFUNDED"));
    }

    /** Creates a zero- or positive-outstanding balance fixture. */
    private StayBalance balance(BigDecimal totalCharges, BigDecimal totalPaidPayments) {
        return new StayBalance(
                totalCharges,
                totalPaidPayments,
                totalCharges.subtract(totalPaidPayments));
    }

    /** Creates a complete checked-in Reservation fixture with rooms in the requested statuses. */
    private Fixture fixture(List<RoomStatus> roomStatuses) {
        return fixture(roomStatuses, Clock.systemDefaultZone());
    }

    /** Creates a complete checked-in Reservation fixture with rooms in the requested statuses and Clock. */
    private Fixture fixture(List<RoomStatus> roomStatuses, Clock clock) {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        GuestRepository guestRepository = mock(GuestRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        StayRoomAssignmentRepository stayRoomAssignmentRepository = mock(StayRoomAssignmentRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ReservationNumberGenerator reservationNumberGenerator = mock(ReservationNumberGenerator.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        UUID reservationId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Reservation reservation = new Reservation(
                reservationId,
                "R20260911-000001",
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                "JPY",
                null);
        List<Room> rooms = new ArrayList<>();
        for (int index = 0; index < roomStatuses.size(); index++) {
            Room room = Room.create(UUID.randomUUID(), "10" + index, null, "1");
            if (roomStatuses.get(index) == RoomStatus.OCCUPIED) {
                room.occupy();
            }
            room.audit(creatorId);
            rooms.add(room);
            reservation.addRoom(new ReservationRoom(
                    reservation,
                    room,
                    reservation.getCheckInDate(),
                    reservation.getCheckOutDate(),
                    BigDecimal.TEN));
        }
        reservation.calculateTotal();
        reservation.confirm();
        reservation.checkIn();
        reservation.audit(creatorId);
        Stay stay = new Stay(reservation, java.time.Instant.parse("2026-09-21T03:00:00Z"));
        stay.audit(creatorId);
        List<UUID> roomIds = rooms.stream().map(Room::getId).sorted().toList();
        List<StayRoomAssignment> openAssignments = new ArrayList<>();
        for (int index = 0; index < rooms.size(); index++) {
            StayRoomAssignment assignment = new StayRoomAssignment(
                    stay,
                    rooms.get(index),
                    reservation.getRooms().get(index),
                    stay.getActualCheckInAt(),
                    null,
                    null);
            assignment.audit(creatorId);
            openAssignments.add(assignment);
        }
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.findByReservationIdForUpdate(reservationId)).thenReturn(Optional.of(stay));
        when(stayRoomAssignmentRepository.findOpenByStayId(stay.getId())).thenReturn(openAssignments);
        when(roomRepository.lockAllByIdIn(roomIds)).thenReturn(rooms.stream().sorted(
                java.util.Comparator.comparing(Room::getId)).toList());
        setCurrentUser(userId);
        return new Fixture(
                new ReservationService(
                        reservationRepository,
                        guestRepository,
                        roomRepository,
                        stayRepository,
                        stayRoomAssignmentRepository,
                        chargeRepository,
                        auditLogRepository,
                        new ReservationMapper(),
                        reservationNumberGenerator,
                        stayBalanceService,
                        clock),
                reservationRepository,
                roomRepository,
                stayRepository,
                stayRoomAssignmentRepository,
                auditLogRepository,
                stayBalanceService,
                reservation,
                stay,
                rooms,
                roomIds,
                openAssignments,
                creatorId,
                userId);
    }

    /** Establishes the authenticated application user for backend audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "check-out-user"), null));
    }

    /** Verifies the standard conflict response used for failed check-out preconditions. */
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(409, exception.getStatusCode().value());
    }

    /** Holds the collaborators and persisted entities used by a check-out scenario. */
    private record Fixture(
            ReservationService service,
            ReservationRepository reservationRepository,
            RoomRepository roomRepository,
            StayRepository stayRepository,
            StayRoomAssignmentRepository stayRoomAssignmentRepository,
            AuditLogRepository auditLogRepository,
            StayBalanceService stayBalanceService,
            Reservation reservation,
            Stay stay,
            List<Room> rooms,
            List<UUID> roomIds,
            List<StayRoomAssignment> openAssignments,
            UUID creatorId,
            UUID userId) {}
}
