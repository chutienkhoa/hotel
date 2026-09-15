package com.example.hotel.repository.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.room.request.RoomSearchCriteria;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.service.room.RoomQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Room Management list filters and pagination against real PostgreSQL filtering,
 * exercising {@link RoomQueryService#findPage} end-to-end rather than a mocked specification.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomRepositorySearchIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE_ROOM_TYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoomQueryService roomQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Supplies Testcontainers database connection properties. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Confirms no active filters returns every Room ordered deterministically by room number. */
    @Test
    @Transactional
    void shouldReturnAllRoomsOrderedByRoomNumberWhenNoFilterIsActive() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = roomQueryService.findPage(new RoomSearchCriteria(), 0);

        assertEquals(3, page.getTotalElements());
        assertEquals(
                List.of("101", "101A", "202"),
                page.getContent().stream().map(RoomResponse::roomNumber).toList());
    }

    /** Confirms a partial Room Number filter matches every Room whose number contains the fragment. */
    @Test
    @Transactional
    void shouldMatchRoomNumberPartially() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setRoomNumber("  101  "));

        assertEquals(2, page.getTotalElements());
        assertEquals(
                List.of("101", "101A"),
                page.getContent().stream().map(RoomResponse::roomNumber).toList());
    }

    /** Confirms Room Number matching is case-insensitive. */
    @Test
    @Transactional
    void shouldMatchRoomNumberCaseInsensitively() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setRoomNumber("101a"));

        assertSingleMatch(page, "101A");
    }

    /** Confirms the Room Type filter matches exactly by the RoomType's stable identifier. */
    @Test
    @Transactional
    void shouldMatchRoomTypeExactly() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setRoomTypeId(SINGLE_ROOM_TYPE_ID));

        assertEquals(2, page.getTotalElements());
        assertEquals(
                List.of("101", "101A"),
                page.getContent().stream().map(RoomResponse::roomNumber).toList());
    }

    /** Confirms the Floor filter matches exactly. */
    @Test
    @Transactional
    void shouldMatchFloorExactly() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setFloor("2"));

        assertSingleMatch(page, "202");
    }

    /** Confirms the Status filter matches exactly by the Room status enum value. */
    @Test
    @Transactional
    void shouldMatchStatusExactly() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setStatus(RoomStatus.OCCUPIED));

        assertSingleMatch(page, "202");
    }

    /** Confirms a blank filter value is ignored rather than treated as a literal empty match. */
    @Test
    @Transactional
    void shouldIgnoreBlankFilterValues() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> {
            criteria.setRoomNumber("   ");
            criteria.setFloor(null);
        });

        assertEquals(3, page.getTotalElements());
    }

    /** Confirms two populated filters combine with AND semantics, not OR semantics. */
    @Test
    @Transactional
    void shouldCombineMultipleFiltersWithAndSemantics() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> nonMatching = search(criteria -> {
            criteria.setRoomTypeId(DOUBLE_ROOM_TYPE_ID);
            criteria.setStatus(RoomStatus.AVAILABLE);
        });

        assertEquals(
                0,
                nonMatching.getTotalElements(),
                "roomTypeId=DOUBLE (matches 202 only) AND status=AVAILABLE (matches 101 only) "
                        + "must match neither Room under AND semantics");
    }

    /** Confirms a filter matching no Room returns an empty page rather than falling back to all Rooms. */
    @Test
    @Transactional
    void shouldReturnNoResultsWhenFilterMatchesNoRoom() {
        UUID userId = insertUser();
        seedThreeRooms(userId);

        Page<RoomResponse> page = search(criteria -> criteria.setFloor("99"));

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    /** Confirms more than one page size worth of Rooms paginates deterministically by room number. */
    @Test
    @Transactional
    void shouldPaginateAcrossMultiplePagesWhenMoreThanTenRoomsExist() {
        UUID userId = insertUser();
        for (int i = 11; i >= 1; i--) {
            insertRoom(userId, String.format("P%05d", i), SINGLE_ROOM_TYPE_ID, "1", RoomStatus.AVAILABLE);
        }

        Page<RoomResponse> firstPage = roomQueryService.findPage(new RoomSearchCriteria(), 0);
        Page<RoomResponse> secondPage = roomQueryService.findPage(new RoomSearchCriteria(), 1);

        assertEquals(11, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(10, firstPage.getContent().size());
        assertEquals(1, secondPage.getContent().size());
        assertEquals(
                List.of("P00001", "P00002", "P00003", "P00004", "P00005",
                        "P00006", "P00007", "P00008", "P00009", "P00010"),
                firstPage.getContent().stream().map(RoomResponse::roomNumber).toList());
        assertEquals("P00011", secondPage.getContent().get(0).roomNumber());
    }

    /** Runs a normalized Room filter search through the production query service. */
    private Page<RoomResponse> search(Consumer<RoomSearchCriteria> configurer) {
        RoomSearchCriteria criteria = new RoomSearchCriteria();
        configurer.accept(criteria);
        criteria.normalize();
        return roomQueryService.findPage(criteria, 0);
    }

    /** Asserts a search result contains exactly one Room with the expected room number. */
    private void assertSingleMatch(Page<RoomResponse> page, String expectedRoomNumber) {
        assertEquals(1, page.getTotalElements());
        assertEquals(expectedRoomNumber, page.getContent().get(0).roomNumber());
    }

    /** Inserts three distinguishable Rooms covering every filterable field. */
    private void seedThreeRooms(UUID userId) {
        insertRoom(userId, "101", SINGLE_ROOM_TYPE_ID, "1", RoomStatus.AVAILABLE);
        insertRoom(userId, "101A", SINGLE_ROOM_TYPE_ID, "1", RoomStatus.DIRTY);
        insertRoom(userId, "202", DOUBLE_ROOM_TYPE_ID, "2", RoomStatus.OCCUPIED);
    }

    /** Inserts one authenticated audit user required by room fixture rows. */
    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId,
                "room-search-" + userId,
                "not-used-in-test",
                Instant.now(),
                Instant.now());
        return userId;
    }

    /** Inserts one Room fixture row referencing an approved seeded RoomType. */
    private void insertRoom(
            UUID userId, String roomNumber, UUID roomTypeId, String floor, RoomStatus status) {
        jdbcTemplate.update(
                "INSERT INTO room (id, room_number, room_type_id, floor, status, active, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?)",
                UUID.randomUUID(),
                roomNumber,
                roomTypeId,
                floor,
                status.name(),
                Instant.now(),
                userId,
                Instant.now(),
                userId);
    }
}
