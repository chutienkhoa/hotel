package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Room inventory period migration against PostgreSQL: the honest install-time baseline
 * for existing Rooms, the fresh-database case, and the database protections of the table.
 */
@Testcontainers(disabledWithoutDocker = true)
class RoomInventoryPeriodMigrationIntegrationTest {

    private static final Instant ROOM_CREATED_AT = Instant.parse("2020-01-01T00:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms every pre-existing Room gets one open BOOTSTRAP period reflecting its CURRENT type and status. */
    @Test
    void shouldBootstrapExistingRoomsWithCurrentTypeAndStatusWithoutBackdating() {
        DataSource dataSource = dataSource();
        clean(dataSource);
        Flyway.configure().dataSource(dataSource).target("27").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<UUID> types = jdbc.queryForList("SELECT id FROM room_type ORDER BY code", UUID.class);
        String[] statuses = {"AVAILABLE", "OCCUPIED", "DIRTY", "CLEANING", "MAINTENANCE", "OUT_OF_ORDER"};
        UUID[] rooms = new UUID[statuses.length];
        for (int index = 0; index < statuses.length; index++) {
            rooms[index] = insertRoom(jdbc, "R" + index, types.get(index % types.size()), statuses[index]);
        }
        Instant beforeMigration = Instant.now().minusSeconds(5);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals(statuses.length, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period"));
        for (int index = 0; index < statuses.length; index++) {
            List<Map<String, Object>> periods = jdbc.queryForList(
                    "SELECT room_type_id, unavailable_reason, origin, effective_from, effective_to, created_by, "
                            + "updated_by FROM room_inventory_period WHERE room_id = ?", rooms[index]);
            assertEquals(1, periods.size());
            Map<String, Object> period = periods.get(0);
            assertEquals(types.get(index % types.size()), period.get("room_type_id"));
            assertEquals("BOOTSTRAP", period.get("origin"));
            assertNull(period.get("effective_to"));
            assertNull(period.get("created_by"));
            assertNull(period.get("updated_by"));
            String expectedReason = switch (statuses[index]) {
                case "MAINTENANCE" -> "MAINTENANCE";
                case "OUT_OF_ORDER" -> "OUT_OF_ORDER";
                default -> null;
            };
            assertEquals(expectedReason, period.get("unavailable_reason"));
            Instant effectiveFrom = ((Timestamp) period.get("effective_from")).toInstant();
            assertTrue(effectiveFrom.isAfter(beforeMigration), "Bootstrap must start at install time, not room.created_at");
            assertFalse(effectiveFrom.equals(ROOM_CREATED_AT));
        }
    }

    /** Confirms a fresh database gets no baseline rows, so no history boundary is fabricated. */
    @Test
    void shouldCreateNoBootstrapRowsOnFreshDatabase() {
        DataSource dataSource = dataSource();
        clean(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period"));
    }

    /** Confirms the table rejects a second open period, duplicate starts, empty intervals and invalid enum values. */
    @Test
    void shouldProtectPeriodInvariantsInDatabase() {
        DataSource dataSource = dataSource();
        clean(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID type = jdbc.queryForObject("SELECT id FROM room_type LIMIT 1", UUID.class);
        UUID room = insertRoom(jdbc, "P1", type, "AVAILABLE");
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        insertPeriod(jdbc, room, type, null, "RECORDED", from, null);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, null, "RECORDED", from.plusSeconds(60), null),
                "a second open period for one Room");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, null, "RECORDED", from, from.plusSeconds(60)),
                "a duplicate (room_id, effective_from)");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, null, "RECORDED", from.minusSeconds(3600), from.minusSeconds(3600)),
                "effective_to equal to effective_from");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, null, "RECORDED", from.minusSeconds(7200), from.minusSeconds(9000)),
                "effective_to before effective_from");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, "OCCUPIED", "RECORDED", from.minusSeconds(3600), from),
                "an unavailable reason outside MAINTENANCE and OUT_OF_ORDER");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPeriod(jdbc, room, type, null, "MIGRATED", from.minusSeconds(3600), from),
                "an origin outside BOOTSTRAP and RECORDED");
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period WHERE room_id = '" + room + "'"));
    }

    private UUID insertRoom(JdbcTemplate jdbc, String number, UUID typeId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO room (id, room_number, room_type_id, floor, status, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '1', ?, TRUE, ?, ?)",
                id, number, typeId, status, Timestamp.from(ROOM_CREATED_AT), Timestamp.from(ROOM_CREATED_AT));
        return id;
    }

    private void insertPeriod(
            JdbcTemplate jdbc, UUID room, UUID type, String reason, String origin, Instant from, Instant to) {
        jdbc.update(
                "INSERT INTO room_inventory_period (id, room_id, room_type_id, unavailable_reason, origin, "
                        + "effective_from, effective_to, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), room, type, reason, origin, Timestamp.from(from), to == null ? null : Timestamp.from(to));
    }

    private void clean(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
    }

    private int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
