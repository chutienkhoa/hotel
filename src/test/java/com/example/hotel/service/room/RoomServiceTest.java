package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
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
                        RoomStatus.MAINTENANCE, RoomStatus.AVAILABLE, (Consumer<Room>) Room::finishMaintenance),
                Arguments.of(
                        RoomStatus.AVAILABLE, RoomStatus.OUT_OF_ORDER, (Consumer<Room>) Room::markOutOfOrder),
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
                Arguments.of(RoomStatus.CLEANING, (Consumer<Room>) Room::startMaintenance),
                Arguments.of(RoomStatus.AVAILABLE, (Consumer<Room>) Room::finishMaintenance),
                Arguments.of(RoomStatus.MAINTENANCE, (Consumer<Room>) Room::markOutOfOrder),
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
                Arguments.of("finishMaintenance", RoomStatus.MAINTENANCE, RoomStatus.AVAILABLE),
                Arguments.of("markOutOfOrder", RoomStatus.AVAILABLE, RoomStatus.OUT_OF_ORDER),
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
                Arguments.of("startMaintenance", RoomStatus.CLEANING),
                Arguments.of("finishMaintenance", RoomStatus.AVAILABLE),
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
            case "startMaintenance" -> roomService.startMaintenance(roomId);
            case "finishMaintenance" -> roomService.finishMaintenance(roomId);
            case "markOutOfOrder" -> roomService.markOutOfOrder(roomId);
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
        return new RoomService(roomRepository, roomTypeRepository, new RoomMapper());
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
