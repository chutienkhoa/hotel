package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.dto.room.request.RoomUpdateRequest;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Room inventory history through the real Room Management transactions on PostgreSQL:
 * periods are recorded with the Room change, split only when RoomType or sellability changes, and
 * roll back together with the Room.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomInventoryHistoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomInventoryHistoryService history;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID typeA;
    private UUID typeB;
    private int sequence;

    /**
     * Supplies the Testcontainers PostgreSQL connection to Spring.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Clears Room data, creates the acting user and selects two RoomTypes. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM room_inventory_period");
        jdbc.update("DELETE FROM room");
        sequence = 0;
        user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', TRUE, now(), now())",
                user, "tester-" + user);
        List<UUID> types = jdbc.queryForList("SELECT id FROM room_type ORDER BY code", UUID.class);
        typeA = types.get(0);
        typeB = types.get(1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "tester"), null));
    }

    /** Clears the authentication established by the test. */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creating a Room opens exactly one RECORDED sellable period with its RoomType. */
    @Test
    void shouldOpenInitialPeriodWhenRoomIsCreated() {
        RoomResponse room = create();

        List<Map<String, Object>> periods = periods(room.id());
        assertEquals(1, periods.size());
        assertEquals(typeA, periods.get(0).get("room_type_id"));
        assertNull(periods.get(0).get("unavailable_reason"));
        assertEquals("RECORDED", periods.get(0).get("origin"));
        assertNull(periods.get(0).get("effective_to"));
        assertEquals(user, periods.get(0).get("created_by"));
    }

    /** Confirms OUT_OF_ORDER and MAINTENANCE cycles each split into contiguous periods and record the reason. */
    @Test
    void shouldSplitContiguouslyOnUnavailableAndRestore() {
        RoomResponse room = create();

        roomService.markOutOfOrder(room.id(), "Plumbing leak");
        roomService.restoreToService(room.id());
        roomService.startMaintenance(room.id(), "Annual AC service");
        roomService.finishMaintenance(room.id());

        List<Map<String, Object>> periods = periods(room.id());
        assertEquals(5, periods.size());
        assertEquals(List.of("OUT_OF_ORDER", "MAINTENANCE"),
                periods.stream().map(p -> (String) p.get("unavailable_reason")).filter(r -> r != null).toList());
        assertEquals(List.of("Plumbing leak", "Annual AC service"),
                periods.stream().map(p -> (String) p.get("reason")).filter(r -> r != null).toList());
        for (int index = 0; index < periods.size() - 1; index++) {
            assertEquals(periods.get(index).get("effective_to"), periods.get(index + 1).get("effective_from"));
        }
        assertNull(periods.get(periods.size() - 1).get("effective_to"));
        assertNull(periods.get(periods.size() - 1).get("reason"), "the final AVAILABLE period must be sellable and reasonless");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_inventory_period WHERE room_id = ? AND effective_to IS NULL",
                Integer.class, room.id()));
    }

    /** Confirms a Room vacated by check-out or Room Change (DIRTY) can go straight into maintenance or out-of-order. */
    @Test
    void shouldSplitFromDirtyIntoMaintenanceOrOutOfOrder() {
        RoomResponse room = create();
        jdbc.update("UPDATE room SET status = 'DIRTY' WHERE id = ?", room.id());

        roomService.startMaintenance(room.id(), "AC compressor failure");

        List<Map<String, Object>> periods = periods(room.id());
        assertEquals(2, periods.size());
        assertEquals("MAINTENANCE", periods.get(1).get("unavailable_reason"));
        assertEquals("AC compressor failure", periods.get(1).get("reason"));
        assertEquals(periods.get(0).get("effective_to"), periods.get(1).get("effective_from"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_inventory_period WHERE room_id = ? AND effective_to IS NULL",
                Integer.class, room.id()));

        roomService.finishMaintenance(room.id());
        jdbc.update("UPDATE room SET status = 'DIRTY' WHERE id = ?", room.id());

        roomService.markOutOfOrder(room.id(), "Water damage");

        periods = periods(room.id());
        assertEquals(4, periods.size());
        assertEquals("OUT_OF_ORDER", periods.get(3).get("unavailable_reason"));
        assertEquals("Water damage", periods.get(3).get("reason"));
        assertNull(periods.get(3).get("effective_to"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_inventory_period WHERE room_id = ? AND effective_to IS NULL",
                Integer.class, room.id()));
    }

    /** Confirms status changes inside sellable inventory create no period. */
    @Test
    void shouldNotSplitForSellableToSellableTransitions() {
        RoomResponse room = create();
        jdbc.update("UPDATE room SET status = 'DIRTY' WHERE id = ?", room.id());

        roomService.startCleaning(room.id());
        roomService.finishCleaning(room.id());

        assertEquals(1, periods(room.id()).size());
    }

    /** Confirms a RoomType change splits, while a number or floor change does not. */
    @Test
    void shouldSplitOnRoomTypeChangeOnly() {
        RoomResponse room = create();

        roomService.update(room.id(), new RoomUpdateRequest("R-renamed", typeA, "9"));
        assertEquals(1, periods(room.id()).size());

        roomService.update(room.id(), new RoomUpdateRequest("R-renamed", typeB, "9"));

        List<Map<String, Object>> periods = periods(room.id());
        assertEquals(2, periods.size());
        assertEquals(typeA, periods.get(0).get("room_type_id"));
        assertEquals(typeB, periods.get(1).get("room_type_id"));
        assertEquals(periods.get(0).get("effective_to"), periods.get(1).get("effective_from"));
    }

    /** Confirms a history failure rolls back the Room mutation in the same transaction. */
    @Test
    void shouldRollBackRoomTransitionWhenOpenPeriodIsMissing() {
        RoomResponse room = create();
        jdbc.update("DELETE FROM room_inventory_period WHERE room_id = ?", room.id());

        assertThrows(IllegalStateException.class, () -> roomService.markOutOfOrder(room.id(), "Plumbing leak"));

        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room.id()));
        assertEquals(0, periods(room.id()).size());
    }

    /** Confirms a Room type change is rolled back too when history cannot be synchronised. */
    @Test
    void shouldRollBackRoomTypeChangeWhenOpenPeriodIsMissing() {
        RoomResponse room = create();
        jdbc.update("DELETE FROM room_inventory_period WHERE room_id = ?", room.id());

        assertThrows(IllegalStateException.class,
                () -> roomService.update(room.id(), new RoomUpdateRequest("R-x", typeB, "9")));

        assertEquals(typeA, jdbc.queryForObject("SELECT room_type_id FROM room WHERE id = ?", UUID.class, room.id()));
    }

    /** Confirms the history boundary comes only from BOOTSTRAP rows in hotel local dates. */
    @Test
    void shouldDeriveHistoryBoundaryFromBootstrapRowsOnly() {
        assertEquals(Optional.empty(), history.firstFullySupportedMonth());
        RoomResponse recorded = create();
        assertEquals(Optional.empty(), history.firstFullySupportedMonth());

        UUID bootstrapRoom = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO room (id, room_number, room_type_id, floor, status, active, created_at, updated_at) "
                        + "VALUES (?, 'B1', ?, '1', 'AVAILABLE', TRUE, now(), now())", bootstrapRoom, typeA);
        insertBootstrap(bootstrapRoom, Instant.parse("2026-09-20T03:00:00Z"));

        assertEquals(Optional.of(YearMonth.of(2026, 10)), history.firstFullySupportedMonth());
        assertEquals(1, periods(recorded.id()).size());
    }

    private void insertBootstrap(UUID room, Instant from) {
        jdbc.update(
                "INSERT INTO room_inventory_period (id, room_id, room_type_id, unavailable_reason, origin, "
                        + "effective_from, effective_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'BOOTSTRAP', ?, NULL, now(), now())",
                UUID.randomUUID(), room, typeA, Timestamp.from(from));
    }

    private RoomResponse create() {
        sequence++;
        return roomService.create(new RoomCreateRequest("R" + sequence, typeA, "1"));
    }

    private List<Map<String, Object>> periods(UUID roomId) {
        return jdbc.queryForList(
                "SELECT room_type_id, unavailable_reason, reason, origin, effective_from, effective_to, created_by "
                        + "FROM room_inventory_period WHERE room_id = ? ORDER BY effective_from",
                roomId);
    }
}
