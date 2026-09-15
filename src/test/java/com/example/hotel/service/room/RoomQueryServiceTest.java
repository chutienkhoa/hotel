package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.request.RoomSearchCriteria;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.mapper.room.RoomMapper;
import com.example.hotel.repository.room.RoomRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Verifies database-backed Room list pagination and filter predicate construction. */
class RoomQueryServiceTest {

    /** Confirms Room list queries use the approved fixed size and room-number ordering. */
    @Test
    void shouldQueryTenRoomsWithRoomNumberOrdering() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomMapper mapper = mock(RoomMapper.class);
        Room room = mock(Room.class);
        RoomResponse summary = new RoomResponse(UUID.randomUUID(), "101", null, "1", "AVAILABLE", true);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(room), Pageable.ofSize(10).withPage(1), 11));
        when(mapper.toResponse(room)).thenReturn(summary);

        var result = new RoomQueryService(repository, mapper).findPage(new RoomSearchCriteria(), 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("roomNumber").getDirection());
        assertEquals(11, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        assertEquals(List.of(summary), result.getContent());
    }

    /** Confirms reservation-create lookup exposes only active AVAILABLE rooms. */
    @Test
    void shouldExposeOnlyActiveAvailableRoomsForReservationCreation() {
        RoomRepository repository = mock(RoomRepository.class);
        Room available = room(UUID.randomUUID(), "101", RoomStatus.AVAILABLE, true);
        when(repository.findByActiveTrueAndStatus(RoomStatus.AVAILABLE)).thenReturn(List.of(available));

        var result = new RoomQueryService(repository, mock(RoomMapper.class)).findAllForReservationCreation();

        assertEquals(List.of("101"), result.stream().map(room -> room.roomNumber()).toList());
        verify(repository).findByActiveTrueAndStatus(RoomStatus.AVAILABLE);
    }

    /** Confirms draft editing retains assigned non-AVAILABLE rooms beside normal eligible choices. */
    @Test
    void shouldRetainAssignedRoomForReservationEditing() {
        RoomRepository repository = mock(RoomRepository.class);
        UUID availableId = UUID.randomUUID();
        UUID assignedId = UUID.randomUUID();
        Room available = room(availableId, "101", RoomStatus.AVAILABLE, true);
        Room assigned = room(assignedId, "102", RoomStatus.OCCUPIED, true);
        when(repository.findByActiveTrueAndStatus(RoomStatus.AVAILABLE)).thenReturn(List.of(available));
        when(repository.findAllById(List.of(assignedId))).thenReturn(List.of(assigned));

        var result = new RoomQueryService(repository, mock(RoomMapper.class))
                .findAllForReservationEditing(List.of(assignedId));

        assertEquals(List.of(availableId, assignedId), result.stream().map(room -> room.id()).toList());
    }

    private Room room(UUID id, String number, RoomStatus status, boolean active) {
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(id);
        when(room.getRoomNumber()).thenReturn(number);
        when(room.getStatus()).thenReturn(status);
        when(room.isActive()).thenReturn(active);
        return room;
    }

    /** Confirms an unfiltered criteria object adds no database predicate for any field. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldIgnoreAbsentFilters() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomQueryService service = new RoomQueryService(repository, mock(RoomMapper.class));
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        criteria.normalize();

        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria)
                .toPredicate(mock(Root.class), mock(CriteriaQuery.class), criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(0, predicatesCaptor.getValue().length);
    }

    /** Confirms a Room Number filter builds a case-insensitive partial-match predicate. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildPartialMatchPredicateForRoomNumber() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomQueryService service = new RoomQueryService(repository, mock(RoomMapper.class));
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        criteria.setRoomNumber("  101  ");
        criteria.normalize();

        Root<Room> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> field = mock(Path.class);
        Expression<String> lowerCaseField = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<String>get("roomNumber")).thenReturn(field);
        when(criteriaBuilder.lower(field)).thenReturn(lowerCaseField);
        when(criteriaBuilder.like(lowerCaseField, "%101%")).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
        verify(root, never()).get("floor");
        verify(root, never()).get("status");
    }

    /** Confirms a Room Type filter builds an exact-match predicate against the RoomType identifier. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildExactMatchPredicateForRoomTypeId() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomQueryService service = new RoomQueryService(repository, mock(RoomMapper.class));
        UUID roomTypeId = UUID.randomUUID();
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        criteria.setRoomTypeId(roomTypeId);
        criteria.normalize();

        Root<Room> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<Object> roomTypePath = mock(Path.class);
        Path<UUID> roomTypeIdPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.get("roomType")).thenReturn(roomTypePath);
        when(roomTypePath.<UUID>get("id")).thenReturn(roomTypeIdPath);
        when(criteriaBuilder.equal(roomTypeIdPath, roomTypeId)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
    }

    /** Confirms a Status filter builds an exact-match predicate against the Room status enum. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildExactMatchPredicateForStatus() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomQueryService service = new RoomQueryService(repository, mock(RoomMapper.class));
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        criteria.setStatus(RoomStatus.OCCUPIED);
        criteria.normalize();

        Root<Room> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<RoomStatus> statusField = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<RoomStatus>get("status")).thenReturn(statusField);
        when(criteriaBuilder.equal(statusField, RoomStatus.OCCUPIED)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
    }

    /** Confirms multiple populated filter fields are AND-combined into one predicate array. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldCombineMultiplePopulatedFiltersWithAndSemantics() {
        RoomRepository repository = mock(RoomRepository.class);
        RoomQueryService service = new RoomQueryService(repository, mock(RoomMapper.class));
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        criteria.setFloor("2");
        criteria.setStatus(RoomStatus.OCCUPIED);
        criteria.normalize();

        Root<Room> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> floorField = mock(Path.class);
        Expression<String> lowerFloor = mock(Expression.class);
        Path<RoomStatus> statusField = mock(Path.class);
        Predicate floorPredicate = mock(Predicate.class);
        Predicate statusPredicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<String>get("floor")).thenReturn(floorField);
        when(criteriaBuilder.lower(floorField)).thenReturn(lowerFloor);
        when(criteriaBuilder.equal(lowerFloor, "2")).thenReturn(floorPredicate);
        when(root.<RoomStatus>get("status")).thenReturn(statusField);
        when(criteriaBuilder.equal(statusField, RoomStatus.OCCUPIED)).thenReturn(statusPredicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(2, predicatesCaptor.getValue().length);
        assertEquals(
                List.of(floorPredicate, statusPredicate),
                List.of(predicatesCaptor.getValue()));
    }
}
