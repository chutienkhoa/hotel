package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.RoomChangeCandidateResponse;
import com.example.hotel.dto.booking.response.RoomChangeFormResponse;
import com.example.hotel.dto.booking.response.RoomChangeReviewResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.service.room.RoomImageService;
import org.springframework.core.io.ByteArrayResource;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the atomic Room Change after Check-in use case and its required preconditions. */
class RoomChangeServiceTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 17);
    private static final LocalDate CHECK_OUT_201 = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHECK_OUT_202 = LocalDate.of(2026, 9, 22);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a normal Room Change closes the old assignment and opens a replacement in the same lineage. */
    @Test
    void shouldChangeRoomAndPreserveLineage() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        Response response = fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null));

        assertEquals("CHECKED_IN", response.status());
        assertEquals(RoomStatus.DIRTY, fixture.room201.getStatus(), "a vacated room needs housekeeping, not AVAILABLE");
        assertEquals(RoomStatus.OCCUPIED, fixture.room305.getStatus());
        assertEquals(RoomStatus.OCCUPIED, fixture.room202.getStatus(), "the other lineage's room must be unaffected");

        ArgumentCaptor<StayRoomAssignment> captor = ArgumentCaptor.forClass(StayRoomAssignment.class);
        verify(fixture.assignmentRepository).save(captor.capture());
        StayRoomAssignment saved = captor.getValue();
        assertEquals(fixture.room305, saved.getRoom());
        assertEquals(fixture.reservationRoom201, saved.getOriginalReservationRoom());
        assertNull(saved.getAssignedTo());
        assertEquals(RoomChangeReason.GUEST_REQUEST, saved.getReason());
        assertEquals(fixture.userId, saved.getCreatedBy());

        assertNotNull(fixture.openAssignment201.getAssignedTo(), "the old assignment must be closed, not deleted");
        verify(fixture.auditLogRepository).save(any(AuditLog.class));
    }

    /** Confirms Review reports each room today and the status Confirm will give it, and mutates nothing. */
    @Test
    void shouldReviewRoomsWithResultingStatesWithoutMutating() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        RoomChangeReviewResponse review = fixture.service.review(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.ROOM_ISSUE, "Điều hòa"));

        assertEquals("201", review.currentRoom().roomNumber());
        assertEquals("Double Room", review.currentRoom().roomTypeName());
        assertEquals("OCCUPIED", review.currentRoom().status());
        assertEquals("DIRTY", review.currentRoom().resultingStatus());
        assertEquals("305", review.targetRoom().roomNumber());
        assertEquals("Triple Room", review.targetRoom().roomTypeName());
        assertEquals(Integer.valueOf(3), review.targetRoom().capacity());
        assertEquals("AVAILABLE", review.targetRoom().status());
        assertEquals("OCCUPIED", review.targetRoom().resultingStatus());
        assertFalse(review.targetRoom().hasPrimaryImage());
        assertEquals(CHECK_IN, review.checkInDate());
        assertEquals(CHECK_OUT_202, review.plannedCheckOutDate());
        assertEquals(5, review.nights());
        assertEquals(RoomChangeReason.ROOM_ISSUE, review.reason());
        assertEquals("Điều hòa", review.notes());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus(), "Review is read-only");
        assertEquals(RoomStatus.AVAILABLE, fixture.room305.getStatus(), "Review is read-only");
        verify(fixture.assignmentRepository, never()).save(any());
        verify(fixture.auditLogRepository, never()).save(any());
    }

    /** Confirms Room Change never touches the immutable ReservationRoom pricing/date snapshot. */
    @Test
    void shouldNotMutateReservationRoomPricingSnapshot() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        BigDecimal originalRate = fixture.reservationRoom201.getNightlyRate();
        BigDecimal originalTotal = fixture.reservationRoom201.getTotalAmount();
        LocalDate originalCheckOut = fixture.reservationRoom201.getCheckOutDate();

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.UPGRADE, null));

        assertEquals(0, originalRate.compareTo(fixture.reservationRoom201.getNightlyRate()));
        assertEquals(0, originalTotal.compareTo(fixture.reservationRoom201.getTotalAmount()));
        assertEquals(originalCheckOut, fixture.reservationRoom201.getCheckOutDate());
        assertEquals(0, fixture.reservation.getTotalAmount().compareTo(
                fixture.reservationRoom201.getTotalAmount().add(fixture.reservationRoom202.getTotalAmount())));
    }

    /**
     * Confirms MAINTENANCE and OUT_OF_ORDER rooms are excluded from Room Change candidates the same way,
     * aligned with the authoritative target-room eligibility check in {@code changeRoom}.
     */
    @Test
    void shouldExcludeMaintenanceAndOutOfOrderRoomsFromCandidates() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.room402.startMaintenance();
        fixture.room305.markOutOfOrder();
        when(fixture.roomRepository.findByActiveTrue())
                .thenReturn(List.of(fixture.room202, fixture.room305, fixture.room402));

        List<UUID> candidateIds = fixture.service.candidateRooms(fixture.reservationId, fixture.room201.getId())
                .stream().map(RoomChangeCandidateResponse::id).toList();

        assertFalse(candidateIds.contains(fixture.room402.getId()), "MAINTENANCE rooms must be excluded");
        assertFalse(candidateIds.contains(fixture.room305.getId()), "OUT_OF_ORDER rooms must be excluded");
        assertFalse(candidateIds.contains(fixture.room201.getId()), "the current room must never be its own candidate");
    }

    /** Confirms the same room cannot be submitted as both current and replacement. */
    @Test
    void shouldRejectSameRoomAsReplacement() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room201.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(400, exception.getStatusCode().value());
        verify(fixture.roomRepository, never()).lockAllByIdIn(anyList());
    }

    /** Confirms reason is required even when a direct service call bypasses controller-level bean validation. */
    @Test
    void shouldRejectMissingReason() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), null, null)));

        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms reason OTHER requires non-blank notes, independent of controller-level validation. */
    @Test
    void shouldRejectOtherReasonWithoutNotes() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.OTHER, "   ")));

        assertEquals(400, exception.getStatusCode().value());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus(), "no partial state on rejection");
    }

    /** Confirms every non-OTHER reason is accepted with empty notes. */
    @Test
    void shouldAllowNonOtherReasonWithoutNotes() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        Response response = fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.OPERATIONAL, null));

        assertEquals("CHECKED_IN", response.status());
    }

    /** Confirms Room Change takes the Stay lock before it locks the rooms (order shared with extension and check-out). */
    @Test
    void shouldLockTheStayBeforeTheRooms() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null));

        var order = org.mockito.Mockito.inOrder(fixture.stayRepository, fixture.roomRepository);
        order.verify(fixture.stayRepository).findByReservationIdForUpdate(fixture.reservationId);
        order.verify(fixture.roomRepository).lockAllByIdIn(anyList());
    }

    /** Confirms Room Change is rejected once today reaches the CURRENT planned check-out (Reservation.checkOutDate). */
    @Test
    void shouldRejectOnOrAfterPlannedCheckOutDate() {
        Fixture fixture = fixture(clockOn(CHECK_OUT_202));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
        verify(fixture.roomRepository, never()).lockAllByIdIn(anyList());
    }

    /** Confirms an inactive or unavailable target Room is rejected after locking. */
    @Test
    void shouldRejectUnavailableTargetRoom() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.room305.markOutOfOrder();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus(), "no partial state on rejection");
    }

    /** Confirms a target Room with a conflicting future overlap is rejected. */
    @Test
    void shouldRejectTargetRoomWithOverlap() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findRoomIdsWithInventoryConflict(
                any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(List.of(fixture.room305.getId()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms Room Change is rejected outside an active CHECKED_IN Reservation/Stay. */
    @Test
    void shouldRejectWhenReservationNotCheckedIn() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.reservation.checkOut();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms a lost race on the same source assignment is rejected after acquiring the lock. */
    @Test
    void shouldRejectWhenSourceAssignmentAlreadyClosedByConcurrentChange() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.assignmentRepository.findOpenByStayIdAndRoomId(fixture.stay.getId(), fixture.room201.getId()))
                .thenReturn(Optional.of(fixture.openAssignment201))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms a lost race on the same target Room is rejected once it is no longer AVAILABLE. */
    @Test
    void shouldRejectWhenTargetRoomTakenByConcurrentChange() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.room305.occupy();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service.changeRoom(
                        fixture.reservationId,
                        fixture.room201.getId(),
                        new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms 201 -> 305 -> 402 preserves the original lineage across two sequential changes. */
    @Test
    void shouldPreserveLineageAcrossMultipleChanges() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.ROOM_ISSUE, null));

        // Simulate the follow-up read after the first change persisted its new open assignment.
        StayRoomAssignment openAt305 = new StayRoomAssignment(
                fixture.stay, fixture.room305, fixture.reservationRoom201, Instant.now(fixture.clock), null, null);
        when(fixture.assignmentRepository.findOpenByStayIdAndRoomId(fixture.stay.getId(), fixture.room305.getId()))
                .thenReturn(Optional.of(openAt305));
        when(fixture.roomRepository.lockAllByIdIn(
                List.of(fixture.room305.getId(), fixture.room402.getId()).stream().sorted().toList()))
                .thenReturn(List.of(fixture.room305, fixture.room402).stream()
                        .sorted(java.util.Comparator.comparing(Room::getId))
                        .toList());

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room305.getId(),
                new RoomChangeRequest(fixture.room402.getId(), RoomChangeReason.ROOM_ISSUE, null));

        ArgumentCaptor<StayRoomAssignment> captor = ArgumentCaptor.forClass(StayRoomAssignment.class);
        verify(fixture.assignmentRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (StayRoomAssignment saved : captor.getAllValues()) {
            assertEquals(fixture.reservationRoom201, saved.getOriginalReservationRoom());
        }
        assertEquals(RoomStatus.DIRTY, fixture.room201.getStatus());
        assertEquals(RoomStatus.DIRTY, fixture.room305.getStatus());
        assertEquals(RoomStatus.OCCUPIED, fixture.room402.getStatus());
    }

    /** Confirms the vacated room cannot be used for an immediate check-in while DIRTY. */
    @Test
    void shouldNotAllowImmediateCheckInIntoTheVacatedRoomWhileDirty() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null));

        assertTrue(!fixture.room201.isReadyForCheckIn());
        assertThrows(IllegalStateException.class, fixture.room201::occupy);
        assertEquals(RoomStatus.DIRTY, fixture.room201.getStatus());
    }

    /** Confirms the vacated room returns to service only through the existing DIRTY, CLEANING, AVAILABLE transitions. */
    @Test
    void shouldReturnVacatedRoomToServiceThroughTheHousekeepingTransitions() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null));

        assertThrows(IllegalStateException.class, fixture.room201::finishCleaning, "cannot skip CLEANING");
        fixture.room201.startCleaning();
        assertEquals(RoomStatus.CLEANING, fixture.room201.getStatus());
        assertTrue(!fixture.room201.isReadyForCheckIn());
        fixture.room201.finishCleaning();

        assertEquals(RoomStatus.AVAILABLE, fixture.room201.getStatus());
        assertTrue(fixture.room201.isReadyForCheckIn());
    }

    /** Confirms assignment history stays valid: old assignment closed at the change instant, new one opened at it. */
    @Test
    void shouldKeepAssignmentHistoryAndReasonWhileDirtyingTheOldRoom() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.ROOM_ISSUE, "AC broken"));

        ArgumentCaptor<StayRoomAssignment> captor = ArgumentCaptor.forClass(StayRoomAssignment.class);
        verify(fixture.assignmentRepository).save(captor.capture());
        StayRoomAssignment opened = captor.getValue();
        assertEquals(fixture.openAssignment201.getAssignedTo(), opened.getAssignedFrom(), "no gap and no overlap");
        assertEquals(RoomChangeReason.ROOM_ISSUE, opened.getReason());
        assertEquals("AC broken", opened.getNotes());
        assertNull(opened.getAssignedTo());
        assertEquals(RoomStatus.DIRTY, fixture.room201.getStatus());
    }

    /** Confirms a rejected change dirties nothing: the old room stays OCCUPIED, its assignment open, nothing saved. */
    @Test
    void shouldNotDirtyOrMoveAnythingWhenTheChangeIsRejected() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        fixture.room305.markOutOfOrder();

        assertThrows(ResponseStatusException.class, () -> fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus());
        assertEquals(RoomStatus.OUT_OF_ORDER, fixture.room305.getStatus());
        assertNull(fixture.openAssignment201.getAssignedTo());
        verify(fixture.assignmentRepository, org.mockito.Mockito.never()).save(any(StayRoomAssignment.class));
        verify(fixture.auditLogRepository, org.mockito.Mockito.never()).save(any(AuditLog.class));
    }

    /** Confirms the form context is the open assignment room, with the booked lineage pricing and Stay facts. */
    @Test
    void shouldExposeCurrentRoomFromOpenAssignmentWithBookedPricing() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        RoomChangeFormResponse form = fixture.service.formView(fixture.reservationId, fixture.room201.getId());

        assertEquals("201", form.currentRoomNumber());
        assertEquals("Double Room", form.currentRoomTypeName());
        assertEquals("OCCUPIED", form.currentRoomStatus());
        assertEquals(CHECK_IN, form.checkInDate());
        assertEquals(CHECK_OUT_202, form.checkOutDate());
        assertEquals(5, form.nights());
        assertEquals(0, fixture.reservationRoom201.getNightlyRate().compareTo(form.nightlyRate()));
        assertEquals(0, fixture.reservationRoom201.getTotalAmount().compareTo(form.totalAmount()));
        assertEquals("VND", form.currency());
    }

    /** Confirms the form context carries the primary guest's code and full name, and tolerates a Reservation with no guest. */
    @Test
    void shouldExposeThePrimaryGuestCodeAndFullName() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        assertEquals(null, fixture.service.formView(fixture.reservationId, fixture.room201.getId()).guestCode());

        org.springframework.test.util.ReflectionTestUtils.setField(fixture.reservation, "guest",
                com.example.hotel.entity.customer.Guest.create(
                        UUID.randomUUID(), "DEMO-G062", " Linh ", "Do", null, null, "Vietnam", null, null));
        RoomChangeFormResponse form = fixture.service.formView(fixture.reservationId, fixture.room201.getId());

        assertEquals("DEMO-G062", form.guestCode());
        assertEquals("Linh Do", form.guestFullName());
        assertEquals("R20260917-000001", form.reservationNumber());
    }

    /** Confirms each replacement candidate carries its Room Type name and capacity for display. */
    @Test
    void shouldExposeRoomTypeAndCapacityOnCandidates() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(fixture.room305));

        List<RoomChangeCandidateResponse> candidates =
                fixture.service.candidateRooms(fixture.reservationId, fixture.room201.getId());

        assertEquals(1, candidates.size());
        assertEquals(fixture.room305.getId(), candidates.get(0).id());
        assertEquals("305", candidates.get(0).roomNumber());
        assertEquals("Triple Room", candidates.get(0).roomTypeName());
        assertEquals(Integer.valueOf(3), candidates.get(0).capacity());
    }

    /** Confirms Room Change is refused when the reservation has no active Stay to change, and nothing is persisted. */
    @Test
    void shouldRejectChangeWhenNoActiveStayExists() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.stayRepository.findByReservationIdForUpdate(fixture.reservationId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus());
        verify(fixture.assignmentRepository, never()).save(any(StayRoomAssignment.class));
    }

    /** Confirms an unknown replacement room is rejected as not found and nothing is persisted. */
    @Test
    void shouldRejectUnknownReplacementRoom() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        UUID unknownRoomId = UUID.randomUUID();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(unknownRoomId, RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(404, exception.getStatusCode().value());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus());
        verify(fixture.assignmentRepository, never()).save(any(StayRoomAssignment.class));
    }

    /**
     * Confirms a room that is not the Stay's open assignment cannot be changed, so a multi-room Stay can only
     * move the one room the user selected.
     */
    @Test
    void shouldRejectChangeForRoomWithoutOpenAssignment() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> fixture.service.changeRoom(
                fixture.reservationId,
                fixture.room305.getId(),
                new RoomChangeRequest(fixture.room201.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals(RoomStatus.OCCUPIED, fixture.room201.getStatus());
        verify(fixture.assignmentRepository, never()).save(any(StayRoomAssignment.class));
    }

    /** Confirms the form context is refused for a room that is not the Stay's open assignment. */
    @Test
    void shouldRejectFormViewForRoomWithoutOpenAssignment() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> fixture.service.formView(fixture.reservationId, fixture.room305.getId()));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Builds a fixed Clock reporting the supplied business date at a stable time of day. */
    private Clock clockOn(LocalDate date) {
        return Clock.fixed(date.atTime(10, 0).atZone(ZONE).toInstant(), ZONE);
    }

    /** Builds a complete CHECKED_IN two-room fixture ready for a Room Change test. */
    private Fixture fixture(Clock clock) {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        StayRoomAssignmentRepository assignmentRepository = mock(StayRoomAssignmentRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Reservation reservation = new Reservation(
                reservationId, "R20260917-000001", null, CHECK_IN, CHECK_OUT_202, "VND", null);

        Room room201 = Room.create(UUID.randomUUID(), "201", roomType("Double Room", 2), "2");
        Room room202 = Room.create(UUID.randomUUID(), "202", roomType("Double Room", 2), "2");
        Room room305 = Room.create(UUID.randomUUID(), "305", roomType("Triple Room", 3), "3");
        Room room402 = Room.create(UUID.randomUUID(), "402", roomType("Family Room", 4), "4");
        room201.occupy();
        room202.occupy();

        ReservationRoom reservationRoom201 =
                new ReservationRoom(reservation, room201, CHECK_IN, CHECK_OUT_201, new BigDecimal("1000000"));
        ReservationRoom reservationRoom202 =
                new ReservationRoom(reservation, room202, CHECK_IN, CHECK_OUT_202, new BigDecimal("1200000"));
        reservation.addRoom(reservationRoom201);
        reservation.addRoom(reservationRoom202);
        reservation.calculateTotal();
        reservation.confirm();
        reservation.checkIn();

        Stay stay = new Stay(reservation, java.time.Instant.parse("2026-09-21T03:00:00Z"));
        stay.audit(userId);

        StayRoomAssignment openAssignment201 =
                new StayRoomAssignment(stay, room201, reservationRoom201, stay.getActualCheckInAt(), null, null);
        StayRoomAssignment openAssignment202 =
                new StayRoomAssignment(stay, room202, reservationRoom202, stay.getActualCheckInAt(), null, null);

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(stayRepository.findByReservationId(reservationId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByReservationIdForUpdate(reservationId)).thenReturn(Optional.of(stay));
        when(assignmentRepository.findOpenByStayIdAndRoomId(stay.getId(), room201.getId()))
                .thenReturn(Optional.of(openAssignment201));
        when(assignmentRepository.findOpenByStayIdAndRoomId(stay.getId(), room202.getId()))
                .thenReturn(Optional.of(openAssignment202));
        when(roomRepository.findById(room305.getId())).thenReturn(Optional.of(room305));
        List<UUID> lockIds = List.of(room201.getId(), room305.getId()).stream().sorted().toList();
        when(roomRepository.lockAllByIdIn(lockIds)).thenReturn(
                List.of(room201, room305).stream().sorted(java.util.Comparator.comparing(Room::getId)).toList());

        setCurrentUser(userId);

        RoomImageService roomImageService = mock(RoomImageService.class);
        RoomChangeService service = new RoomChangeService(
                reservationRepository,
                stayRepository,
                assignmentRepository,
                roomRepository,
                auditLogRepository,
                new ReservationMapper(),
                new com.example.hotel.service.room.RoomAvailabilityService(roomRepository, clock),
                roomImageService,
                clock);

        return new Fixture(
                service,
                reservationRepository,
                stayRepository,
                assignmentRepository,
                roomRepository,
                auditLogRepository,
                reservation,
                reservationId,
                stay,
                room201,
                room202,
                room305,
                room402,
                reservationRoom201,
                reservationRoom202,
                openAssignment201,
                userId,
                clock,
                roomImageService);
    }

    /** Confirms the form reports Room Change as open before the planned check-out and closed on that date. */
    @Test
    void shouldReportWindowClosedOnPlannedCheckOutDate() {
        Fixture open = fixture(clockOn(CHECK_IN.plusDays(1)));
        Fixture closed = fixture(clockOn(CHECK_OUT_202));

        assertTrue(open.service.formView(open.reservationId, open.room201.getId()).roomChangeOpen());
        assertFalse(closed.service.formView(closed.reservationId, closed.room201.getId()).roomChangeOpen());
    }

    /** Confirms the review step refuses a change on the planned check-out date, using the same rule as confirm. */
    @Test
    void shouldRejectReviewOnOrAfterPlannedCheckOutDate() {
        Fixture fixture = fixture(clockOn(CHECK_OUT_202));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> fixture.service.review(
                fixture.reservationId,
                fixture.room201.getId(),
                new RoomChangeRequest(fixture.room305.getId(), RoomChangeReason.GUEST_REQUEST, null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms each candidate reports whether it has a primary image, from one batched lookup. */
    @Test
    void shouldReportPrimaryImagePresenceOnCandidates() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(fixture.room305));
        when(fixture.roomImageService.roomIdsWithPrimaryImage(anyList())).thenReturn(Set.of(fixture.room305.getId()));

        List<RoomChangeCandidateResponse> candidates =
                fixture.service.candidateRooms(fixture.reservationId, fixture.room201.getId());

        assertTrue(candidates.get(0).hasPrimaryImage());
    }

    /** Confirms the current room's image is served for the Change Room screen. */
    @Test
    void shouldServeCurrentRoomPrimaryImage() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        RoomImageFile image = new RoomImageFile(new ByteArrayResource(new byte[] {1}), "image/png", "room.png");
        when(fixture.roomImageService.loadPrimaryImage(fixture.room201.getId())).thenReturn(Optional.of(image));

        assertEquals(image, fixture.service.changeRoomImage(
                fixture.reservationId, fixture.room201.getId(), fixture.room201.getId()));
    }

    /** Confirms a replacement candidate's primary image is served, and that the image is loaded for that room only. */
    @Test
    void shouldServeCandidatePrimaryImageOnlyForThatRoom() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(fixture.room305));
        RoomImageFile image = new RoomImageFile(new ByteArrayResource(new byte[] {1}), "image/png", "305.png");
        when(fixture.roomImageService.loadPrimaryImage(fixture.room305.getId())).thenReturn(Optional.of(image));

        assertEquals(image, fixture.service.changeRoomImage(
                fixture.reservationId, fixture.room201.getId(), fixture.room305.getId()));
        verify(fixture.roomImageService, never()).loadPrimaryImage(fixture.room402.getId());
    }

    /** Confirms a Room outside this Change Room workflow is not served, even if it has a primary image. */
    @Test
    void shouldNotServeImageOfRoomOutsideTheWorkflow() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(fixture.room305));
        when(fixture.roomImageService.loadPrimaryImage(fixture.room402.getId()))
                .thenReturn(Optional.of(new RoomImageFile(new ByteArrayResource(new byte[] {1}), "image/png", "x")));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                fixture.service.changeRoomImage(fixture.reservationId, fixture.room201.getId(), fixture.room402.getId()));

        assertEquals(404, exception.getStatusCode().value());
        verify(fixture.roomImageService, never()).loadPrimaryImage(fixture.room402.getId());
    }

    /** Confirms a workflow Room with no primary image is reported as not found, so the screen shows its fallback. */
    @Test
    void shouldReturnNotFoundWhenWorkflowRoomHasNoPrimaryImage() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));
        when(fixture.roomRepository.findByActiveTrue()).thenReturn(List.of(fixture.room305));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                fixture.service.changeRoomImage(fixture.reservationId, fixture.room201.getId(), fixture.room305.getId()));

        assertEquals(404, exception.getStatusCode().value());
    }

    /** Confirms the image boundary rejects a current room that is not an open assignment of this Stay. */
    @Test
    void shouldRejectImageRequestForRoomWithoutOpenAssignment() {
        Fixture fixture = fixture(clockOn(CHECK_IN.plusDays(1)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                fixture.service.changeRoomImage(fixture.reservationId, fixture.room305.getId(), fixture.room201.getId()));

        assertEquals(409, exception.getStatusCode().value());
        verify(fixture.roomImageService, never()).loadPrimaryImage(fixture.room201.getId());
    }

    /** Builds a Room Type stub carrying the display name and capacity shown on the Room Change form. */
    private RoomType roomType(String name, int capacity) {
        RoomType roomType = mock(RoomType.class);
        when(roomType.getName()).thenReturn(name);
        when(roomType.getCapacity()).thenReturn(capacity);
        return roomType;
    }

    /** Establishes the application principal used by the Room Change service operation. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "room-change-user"), null));
    }

    /** Groups the collaborators and persisted entities assembled for one Room Change scenario. */
    private record Fixture(
            RoomChangeService service,
            ReservationRepository reservationRepository,
            StayRepository stayRepository,
            StayRoomAssignmentRepository assignmentRepository,
            RoomRepository roomRepository,
            AuditLogRepository auditLogRepository,
            Reservation reservation,
            UUID reservationId,
            Stay stay,
            Room room201,
            Room room202,
            Room room305,
            Room room402,
            ReservationRoom reservationRoom201,
            ReservationRoom reservationRoom202,
            StayRoomAssignment openAssignment201,
            UUID userId,
            Clock clock,
            RoomImageService roomImageService) {}
}
