package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.dto.room.request.RoomUpdateRequest;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.room.RoomMapper;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.repository.room.RoomTypeRepository;
import com.example.hotel.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Room Management owns technical IDs, initial state, mutable fields, and audit users. */
class RoomServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Clears the authentication established by an individual Room Management test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creation supplies a UUID, assigns AVAILABLE, and records the authenticated creator. */
    @Test
    void shouldCreateRoomWithUuidAvailableStatusAndAuditUser() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomType roomType = roomType();
        UUID creatorId = UUID.randomUUID();
        setCurrentUser(creatorId);
        when(roomTypeRepository.findById(roomType.getId())).thenReturn(Optional.of(roomType));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomResponse created = roomService(roomRepository, roomTypeRepository).create(createRequest(roomType.getId()));
        ArgumentCaptor<Room> savedRoom = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(savedRoom.capture());

        assertNotNull(created.id());
        assertEquals(RoomStatus.AVAILABLE.name(), created.status());
        assertTrue(created.active());
        assertEquals(creatorId, savedRoom.getValue().getCreatedBy());
        assertEquals(creatorId, savedRoom.getValue().getUpdatedBy());
    }

    /** Confirms a profile update preserves the UUID, status, active flag, and creator audit value. */
    @Test
    void shouldUpdateOnlyApprovedRoomProfileFieldsAndAuditUpdater() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomType originalRoomType = roomType();
        RoomType updatedRoomType = roomType();
        UUID roomId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Room room = Room.create(roomId, "101", originalRoomType, "1");
        room.audit(creatorId);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomTypeRepository.findById(updatedRoomType.getId())).thenReturn(Optional.of(updatedRoomType));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);

        RoomResponse updated = roomService(roomRepository, roomTypeRepository)
                .update(roomId, new RoomUpdateRequest("202", updatedRoomType.getId(), "2"));

        assertEquals(roomId, updated.id());
        assertEquals("202", updated.roomNumber());
        assertEquals(updatedRoomType.getId(), updated.roomType().id());
        assertEquals("2", updated.floor());
        assertEquals(RoomStatus.AVAILABLE.name(), updated.status());
        assertTrue(updated.active());
        assertEquals(creatorId, room.getCreatedBy());
        assertEquals(updaterId, room.getUpdatedBy());
    }

    /** Confirms creation opens the initial inventory period with the hotel clock Instant and the creator. */
    @Test
    void shouldInitializeInventoryHistoryWhenCreatingRoom() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);
        RoomType roomType = roomType();
        UUID creatorId = UUID.randomUUID();
        setCurrentUser(creatorId);
        when(roomTypeRepository.findById(roomType.getId())).thenReturn(Optional.of(roomType));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        roomService(roomRepository, roomTypeRepository, history).create(createRequest(roomType.getId()));

        ArgumentCaptor<Room> saved = ArgumentCaptor.forClass(Room.class);
        verify(history).initialize(saved.capture(), org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(creatorId));
        assertEquals(roomType, saved.getValue().getRoomType());
    }

    /** Confirms a Room profile update takes the Room write lock and never reads the Room unlocked. */
    @Test
    void shouldLockRoomBeforeUpdatingProfileAndSyncHistory() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);
        RoomType roomType = roomType();
        UUID roomId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Room room = Room.create(roomId, "101", roomType, "1");
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomTypeRepository.findById(roomType.getId())).thenReturn(Optional.of(roomType));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);

        roomService(roomRepository, roomTypeRepository, history)
                .update(roomId, new RoomUpdateRequest("102", roomType.getId(), "2"));

        verify(roomRepository).lockAllByIdIn(List.of(roomId));
        verify(roomRepository, never()).findById(any());
        verify(history).sync(room, NOW, updaterId);
    }

    /** Confirms a status transition synchronises inventory history with the hotel clock Instant and the reason. */
    @Test
    void shouldSyncInventoryHistoryAfterStatusTransition() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);
        UUID roomId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType(), RoomStatus.AVAILABLE);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);

        roomService(roomRepository, roomTypeRepository, history)
                .markOutOfOrder(roomId, "Air conditioner compressor failure");

        assertEquals(RoomStatus.OUT_OF_ORDER, room.getStatus());
        verify(history).sync(room, NOW, updaterId, "Air conditioner compressor failure");
    }

    /** Confirms a history failure propagates, so the surrounding transaction rolls the Room change back. */
    @Test
    void shouldPropagateInventoryHistoryFailureFromTransition() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);
        UUID roomId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType(), RoomStatus.AVAILABLE);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("no open period")).when(history).sync(any(), any(), any(), any());
        setCurrentUser(UUID.randomUUID());

        assertThrows(
                IllegalStateException.class,
                () -> roomService(roomRepository, roomTypeRepository, history)
                        .markOutOfOrder(roomId, "Air conditioner compressor failure"));
    }

    /** Confirms a missing reason is rejected before the Room is even locked. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void shouldRejectBlankReasonForStartMaintenance(String blankReason) {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        setCurrentUser(UUID.randomUUID());

        assertThrows(
                com.example.hotel.exception.LocalizedResponseStatusException.class,
                () -> roomService(roomRepository, roomTypeRepository).startMaintenance(UUID.randomUUID(), blankReason));
        verify(roomRepository, never()).lockAllByIdIn(any());
    }

    /** Confirms a {@code null} reason is rejected the same way as a blank one. */
    @Test
    void shouldRejectNullReasonForMarkOutOfOrder() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        setCurrentUser(UUID.randomUUID());

        assertThrows(
                com.example.hotel.exception.LocalizedResponseStatusException.class,
                () -> roomService(roomRepository, roomTypeRepository).markOutOfOrder(UUID.randomUUID(), null));
        verify(roomRepository, never()).lockAllByIdIn(any());
    }

    /** Confirms surrounding whitespace is trimmed before the reason reaches inventory history. */
    @Test
    void shouldTrimReasonBeforeSyncingHistory() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);
        UUID roomId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType(), RoomStatus.AVAILABLE);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(UUID.randomUUID());

        roomService(roomRepository, roomTypeRepository, history)
                .startMaintenance(roomId, "  AC compressor failure  ");

        verify(history).sync(
                org.mockito.ArgumentMatchers.eq(room),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq("AC compressor failure"));
    }

    /**
     * Confirms the affected-reservations warning queries the repository with the hotel clock's current
     * date and the CONFIRMED status, and maps each row without altering its data.
     */
    @Test
    void shouldFindUpcomingAffectedReservationsUsingHotelClockAndConfirmedStatus() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        UUID roomId = UUID.randomUUID();
        LocalDate checkIn = LocalDate.of(2026, 10, 10);
        LocalDate checkOut = LocalDate.of(2026, 10, 12);
        when(roomRepository.findUpcomingConfirmedReservationRooms(
                        roomId, LocalDate.now(CLOCK), com.example.hotel.entity.booking.ReservationStatus.CONFIRMED))
                .thenReturn(List.of(new com.example.hotel.repository.room.AffectedReservationRow(
                        "R20261010-000001", checkIn, checkOut)));

        List<com.example.hotel.dto.room.response.AffectedReservationResponse> affected =
                roomService(roomRepository, roomTypeRepository).findUpcomingAffectedReservations(roomId);

        assertEquals(1, affected.size());
        assertEquals("R20261010-000001", affected.get(0).reservationNumber());
        assertEquals(checkIn, affected.get(0).checkInDate());
        assertEquals(checkOut, affected.get(0).checkOutDate());
    }

    /** Confirms a Room with no upcoming CONFIRMED reservations returns an empty warning list. */
    @Test
    void shouldReturnEmptyAffectedReservationsWhenNoneExist() {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findUpcomingConfirmedReservationRooms(any(), any(), any())).thenReturn(List.of());

        List<com.example.hotel.dto.room.response.AffectedReservationResponse> affected =
                roomService(roomRepository, roomTypeRepository).findUpcomingAffectedReservations(roomId);

        assertTrue(affected.isEmpty());
    }

    /** Confirms client write contracts do not expose server-controlled Room fields. */
    @Test
    void shouldNotExposeServerControlledFieldsInRoomWriteContracts() {
        assertFalse(hasComponent(RoomCreateRequest.class, "id"));
        assertFalse(hasComponent(RoomCreateRequest.class, "status"));
        assertFalse(hasComponent(RoomCreateRequest.class, "active"));
        assertFalse(hasComponent(RoomCreateRequest.class, "createdBy"));
        assertFalse(hasComponent(RoomCreateRequest.class, "updatedBy"));
        assertFalse(hasComponent(RoomUpdateRequest.class, "id"));
        assertFalse(hasComponent(RoomUpdateRequest.class, "status"));
        assertFalse(hasComponent(RoomUpdateRequest.class, "active"));
        assertFalse(hasComponent(RoomUpdateRequest.class, "createdBy"));
        assertFalse(hasComponent(RoomUpdateRequest.class, "updatedBy"));
    }

    /**
     * Confirms every approved Room Operations B domain operation reaches only its exact target status.
     *
     * @param sourceStatus approved source status
     * @param targetStatus approved target status
     * @param operation explicit domain operation to invoke
     */
    @ParameterizedTest
    @MethodSource("approvedDomainTransitions")
    void shouldApplyApprovedDomainTransitionOnly(
            RoomStatus sourceStatus, RoomStatus targetStatus, Consumer<Room> operation) {
        RoomType roomType = roomType();
        UUID roomId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType, sourceStatus);

        operation.accept(room);

        assertEquals(targetStatus, room.getStatus());
        assertUnchangedRoomProfile(room, roomId, roomType);
    }

    /**
     * Confirms each explicit Room Operations B method rejects a status other than its approved source.
     *
     * @param invalidSourceStatus status not approved for the operation
     * @param operation explicit domain operation to invoke
     */
    @ParameterizedTest
    @MethodSource("invalidDomainTransitions")
    void shouldRejectInvalidDomainTransition(RoomStatus invalidSourceStatus, Consumer<Room> operation) {
        Room room = roomWithStatus(UUID.randomUUID(), roomType(), invalidSourceStatus);

        assertThrows(IllegalStateException.class, () -> operation.accept(room));
        assertEquals(invalidSourceStatus, room.getStatus());
    }

    /** Confirms existing check-in behavior remains the AVAILABLE-to-OCCUPIED transition. */
    @Test
    void shouldKeepExistingCheckInTransitionUnchanged() {
        RoomType roomType = roomType();
        UUID roomId = UUID.randomUUID();
        Room room = Room.create(roomId, "101", roomType, "1");

        room.occupy();

        assertEquals(RoomStatus.OCCUPIED, room.getStatus());
        assertUnchangedRoomProfile(room, roomId, roomType);
    }

    /** Confirms a checkout-dirtied room and a Room-Change-vacated room enter the same housekeeping lifecycle. */
    @Test
    void shouldLetCheckoutAndRoomChangeDirtiedRoomsFollowTheHousekeepingLifecycle() {
        Room checkedOut = Room.create(UUID.randomUUID(), "101", roomType(), "1");
        checkedOut.occupy();
        checkedOut.markDirty();
        Room vacated = Room.create(UUID.randomUUID(), "102", roomType(), "1");
        vacated.occupy();
        vacated.releaseForRoomChange();

        for (Room room : List.of(checkedOut, vacated)) {
            assertEquals(RoomStatus.DIRTY, room.getStatus());
            assertFalse(room.isReadyForCheckIn());
            assertThrows(IllegalStateException.class, room::finishCleaning);
            room.startCleaning();
            assertEquals(RoomStatus.CLEANING, room.getStatus());
            assertFalse(room.isReadyForCheckIn());
            room.finishCleaning();
            assertEquals(RoomStatus.AVAILABLE, room.getStatus());
            assertTrue(room.isReadyForCheckIn());
        }
    }

    /**
     * Confirms each service operation locks the Room, records the authenticated updater, and preserves the creator.
     *
     * @param operationName explicit service operation name
     * @param sourceStatus approved source status
     * @param targetStatus approved target status
     */
    @ParameterizedTest
    @MethodSource("approvedServiceTransitions")
    void shouldApplyServiceTransitionWithLockAndAudit(
            String operationName, RoomStatus sourceStatus, RoomStatus targetStatus) {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        RoomType roomType = roomType();
        UUID roomId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType, sourceStatus);
        room.audit(creatorId);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);

        RoomResponse transitioned = applyOperation(roomService(roomRepository, roomTypeRepository), operationName, roomId);

        verify(roomRepository).lockAllByIdIn(List.of(roomId));
        assertEquals(targetStatus.name(), transitioned.status());
        assertEquals(creatorId, room.getCreatedBy());
        assertEquals(updaterId, room.getUpdatedBy());
        assertUnchangedRoomProfile(room, roomId, roomType);
    }

    /**
     * Confirms invalid service operations are rejected after loading the Room and before persistence.
     *
     * @param operationName explicit service operation name
     * @param invalidSourceStatus status not approved for the operation
     */
    @ParameterizedTest
    @MethodSource("invalidServiceTransitions")
    void shouldRejectInvalidServiceTransition(String operationName, RoomStatus invalidSourceStatus) {
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomTypeRepository roomTypeRepository = mock(RoomTypeRepository.class);
        UUID roomId = UUID.randomUUID();
        Room room = roomWithStatus(roomId, roomType(), invalidSourceStatus);
        when(roomRepository.lockAllByIdIn(List.of(roomId))).thenReturn(List.of(room));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> applyOperation(roomService(roomRepository, roomTypeRepository), operationName, roomId));

        assertEquals(409, exception.getStatusCode().value());
        verify(roomRepository).lockAllByIdIn(List.of(roomId));
    }

    /**
     * Supplies the six approved domain transition mappings.
     *
     * @return source status, target status, and domain operation mappings
     */
    private static Stream<Arguments> approvedDomainTransitions() {
        return Stream.of(
                Arguments.of(RoomStatus.DIRTY, RoomStatus.CLEANING, (Consumer<Room>) Room::startCleaning),
                Arguments.of(RoomStatus.CLEANING, RoomStatus.AVAILABLE, (Consumer<Room>) Room::finishCleaning),
                Arguments.of(
                        RoomStatus.AVAILABLE, RoomStatus.MAINTENANCE, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(
                        RoomStatus.DIRTY, RoomStatus.MAINTENANCE, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(
                        RoomStatus.MAINTENANCE, RoomStatus.AVAILABLE, (Consumer<Room>) Room::finishMaintenance),
                Arguments.of(
                        RoomStatus.AVAILABLE, RoomStatus.OUT_OF_ORDER, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(
                        RoomStatus.DIRTY, RoomStatus.OUT_OF_ORDER, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(
                        RoomStatus.OUT_OF_ORDER, RoomStatus.AVAILABLE, (Consumer<Room>) Room::restoreToService));
    }

    /**
     * Supplies an invalid source status for each explicit domain operation.
     *
     * @return invalid status and domain operation mappings
     */
    private static Stream<Arguments> invalidDomainTransitions() {
        return Stream.of(
                Arguments.of(RoomStatus.AVAILABLE, (Consumer<Room>) Room::startCleaning),
                Arguments.of(RoomStatus.DIRTY, (Consumer<Room>) Room::finishCleaning),
                Arguments.of(RoomStatus.OCCUPIED, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(RoomStatus.CLEANING, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(RoomStatus.MAINTENANCE, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(RoomStatus.OUT_OF_ORDER, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(RoomStatus.AVAILABLE, (Consumer<Room>) Room::finishMaintenance),
                Arguments.of(RoomStatus.OCCUPIED, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(RoomStatus.CLEANING, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(RoomStatus.MAINTENANCE, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(RoomStatus.OUT_OF_ORDER, (Consumer<Room>) Room::markOutOfOrder),
                Arguments.of(RoomStatus.AVAILABLE, (Consumer<Room>) Room::restoreToService));
    }

    /**
     * Supplies the six approved service transition mappings.
     *
     * @return operation name, source status, and target status mappings
     */
    private static Stream<Arguments> approvedServiceTransitions() {
        return Stream.of(
                Arguments.of("startCleaning", RoomStatus.DIRTY, RoomStatus.CLEANING),
                Arguments.of("finishCleaning", RoomStatus.CLEANING, RoomStatus.AVAILABLE),
                Arguments.of("startMaintenance", RoomStatus.AVAILABLE, RoomStatus.MAINTENANCE),
                Arguments.of("startMaintenance", RoomStatus.DIRTY, RoomStatus.MAINTENANCE),
                Arguments.of("finishMaintenance", RoomStatus.MAINTENANCE, RoomStatus.AVAILABLE),
                Arguments.of("markOutOfOrder", RoomStatus.AVAILABLE, RoomStatus.OUT_OF_ORDER),
                Arguments.of("markOutOfOrder", RoomStatus.DIRTY, RoomStatus.OUT_OF_ORDER),
                Arguments.of("restoreToService", RoomStatus.OUT_OF_ORDER, RoomStatus.AVAILABLE));
    }

    /**
     * Supplies an invalid source status for each explicit service operation.
     *
     * @return operation name and invalid source status mappings
     */
    private static Stream<Arguments> invalidServiceTransitions() {
        return Stream.of(
                Arguments.of("startCleaning", RoomStatus.AVAILABLE),
                Arguments.of("finishCleaning", RoomStatus.DIRTY),
                Arguments.of("startMaintenance", RoomStatus.OCCUPIED),
                Arguments.of("startMaintenance", RoomStatus.CLEANING),
                Arguments.of("finishMaintenance", RoomStatus.AVAILABLE),
                Arguments.of("markOutOfOrder", RoomStatus.OCCUPIED),
                Arguments.of("markOutOfOrder", RoomStatus.CLEANING),
                Arguments.of("markOutOfOrder", RoomStatus.MAINTENANCE),
                Arguments.of("restoreToService", RoomStatus.AVAILABLE));
    }

    /**
     * Creates a Room fixture in a selected operational status without introducing a production status setter.
     *
     * @param roomId Room identifier
     * @param roomType required RoomType fixture
     * @param status status to arrange for the test
     * @return the Room fixture
     */
    private Room roomWithStatus(UUID roomId, RoomType roomType, RoomStatus status) {
        Room room = Room.create(roomId, "101", roomType, "1");
        ReflectionTestUtils.setField(room, "status", status);
        return room;
    }

    /**
     * Confirms a transition did not modify any Room profile or active-inventory field.
     *
     * @param room transitioned Room
     * @param roomId expected technical identifier
     * @param roomType expected RoomType reference
     */
    private void assertUnchangedRoomProfile(Room room, UUID roomId, RoomType roomType) {
        assertEquals(roomId, room.getId());
        assertEquals("101", room.getRoomNumber());
        assertEquals(roomType, room.getRoomType());
        assertEquals("1", room.getFloor());
        assertTrue(room.isActive());
    }

    /**
     * Invokes one named explicit Room Operations B service operation.
     *
     * @param roomService Room Management service under test
     * @param operationName approved operation name
     * @param roomId Room identifier
     * @return the transitioned Room response
     */
    private RoomResponse applyOperation(RoomService roomService, String operationName, UUID roomId) {
        return switch (operationName) {
            case "startCleaning" -> roomService.startCleaning(roomId);
            case "finishCleaning" -> roomService.finishCleaning(roomId);
            case "startMaintenance" -> roomService.startMaintenance(roomId, "Air conditioner compressor failure");
            case "finishMaintenance" -> roomService.finishMaintenance(roomId);
            case "markOutOfOrder" -> roomService.markOutOfOrder(roomId, "Air conditioner compressor failure");
            case "restoreToService" -> roomService.restoreToService(roomId);
            default -> throw new IllegalArgumentException("Unexpected Room operation: " + operationName);
        };
    }

    /**
     * Creates a Room Management service with the supplied persistence collaborators.
     *
     * @param roomRepository repository used to manage rooms
     * @param roomTypeRepository repository used to resolve RoomTypes
     * @return the configured service
     */
    private RoomService roomService(RoomRepository roomRepository, RoomTypeRepository roomTypeRepository) {
        return roomService(roomRepository, roomTypeRepository, mock(RoomInventoryHistoryService.class));
    }

    /**
     * Creates a Room Management service with an explicit inventory-history collaborator and the fixed hotel clock.
     *
     * @param roomRepository repository used to manage rooms
     * @param roomTypeRepository repository used to resolve RoomTypes
     * @param inventoryHistory inventory-history collaborator, usually a mock
     * @return the configured service
     */
    private RoomService roomService(
            RoomRepository roomRepository,
            RoomTypeRepository roomTypeRepository,
            RoomInventoryHistoryService inventoryHistory) {
        return new RoomService(roomRepository, roomTypeRepository, new RoomMapper(), inventoryHistory, CLOCK);
    }

    /**
     * Creates a mocked read-only RoomType with the fields needed for response mapping.
     *
     * @return a RoomType fixture
     */
    private RoomType roomType() {
        RoomType roomType = mock(RoomType.class);
        when(roomType.getId()).thenReturn(UUID.randomUUID());
        when(roomType.getCode()).thenReturn("SINGLE");
        when(roomType.getName()).thenReturn("Single");
        return roomType;
    }

    /**
     * Creates valid room creation data.
     *
     * @param roomTypeId required RoomType identifier
     * @return a room creation request
     */
    private RoomCreateRequest createRequest(UUID roomTypeId) {
        return new RoomCreateRequest("101", roomTypeId, "1");
    }

    /**
     * Determines whether one request record exposes a forbidden component.
     *
     * @param requestType request record type to inspect
     * @param componentName component name to find
     * @return {@code true} when the component exists
     */
    private boolean hasComponent(Class<?> requestType, String componentName) {
        return Arrays.stream(requestType.getRecordComponents())
                .anyMatch(component -> component.getName().equals(componentName));
    }

    /**
     * Establishes the application principal used by a Room Management service operation.
     *
     * @param userId authenticated application user identifier
     */
    private void setCurrentUser(UUID userId) {
        CurrentUser user = new CurrentUser(userId, "room-manager");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }
}
