package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the dev seeder gives demo Stays actual-occupancy history and demo Rooms consistent
 * synthetic inventory history, so demo occupancy never exceeds demo sellable inventory.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederInventoryHistoryIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZONE);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms CHECKED_IN stays get open assignments and CHECKED_OUT stays closed ones anchored on the ReservationRoom. */
    @Test
    void shouldSeedAssignmentsMatchingStayAndReservationRoomFacts() {
        JdbcTemplate jdbc = seededDatabase();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.status AS stay_status, s.actual_check_in_at AS s_in, s.actual_check_out_at AS s_out, "
                        + "rr.id AS rr_id, rr.room_id AS rr_room, a.original_reservation_room_id AS anchor, "
                        + "a.room_id AS a_room, a.assigned_from AS a_from, a.assigned_to AS a_to "
                        + "FROM stay s JOIN reservation_room rr ON rr.reservation_id = s.reservation_id "
                        + "LEFT JOIN stay_room_assignment a ON a.stay_id = s.id "
                        + "WHERE s.status IN ('CHECKED_IN', 'CHECKED_OUT')");
        assertFalse(rows.isEmpty());
        boolean sawIn = false;
        boolean sawOut = false;
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("anchor"), "every seeded stay needs an assignment");
            assertEquals(row.get("rr_id"), row.get("anchor"));
            assertEquals(row.get("rr_room"), row.get("a_room"));
            assertEquals(row.get("s_in"), row.get("a_from"));
            if ("CHECKED_IN".equals(row.get("stay_status"))) {
                sawIn = true;
                assertEquals(null, row.get("a_to"));
            } else {
                sawOut = true;
                assertEquals(row.get("s_out"), row.get("a_to"));
            }
        }
        assertTrue(sawIn && sawOut);
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM (SELECT room_id FROM stay_room_assignment "
                + "WHERE assigned_to IS NULL GROUP BY room_id HAVING COUNT(*) > 1) d"));
    }

    /** Confirms every demo Room has one contiguous RECORDED history covering the demo window with one open period. */
    @Test
    void shouldSeedContiguousRecordedInventoryHistoryForEveryDemoRoom() {
        JdbcTemplate jdbc = seededDatabase();
        Instant windowStart = LocalDate.of(2026, 1, 1).atStartOfDay(ZONE).toInstant();

        List<UUID> rooms = jdbc.queryForList("SELECT id FROM room WHERE room_number LIKE 'DEMO-%'", UUID.class);
        assertFalse(rooms.isEmpty());
        for (UUID room : rooms) {
            List<Map<String, Object>> periods = periods(jdbc, room);
            assertFalse(periods.isEmpty());
            assertEquals(windowStart, ((Timestamp) periods.get(0).get("effective_from")).toInstant());
            for (int index = 0; index < periods.size(); index++) {
                assertEquals("RECORDED", periods.get(index).get("origin"));
                if (index < periods.size() - 1) {
                    assertEquals(periods.get(index).get("effective_to"), periods.get(index + 1).get("effective_from"));
                } else {
                    assertEquals(null, periods.get(index).get("effective_to"));
                }
            }
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period WHERE origin = 'BOOTSTRAP'"));
    }

    /**
     * Confirms, under the hotel-night rule, every seeded occupied night falls in a sellable period, no Room
     * is occupied twice for one night, and occupied nights per date never exceed sellable rooms.
     */
    @Test
    void shouldNeverSeedOccupiedNightsOutsideSellableInventory() {
        JdbcTemplate jdbc = seededDatabase();
        LocalDate today = LocalDate.now(CLOCK);
        Map<UUID, List<Map<String, Object>>> periodsByRoom = new HashMap<>();
        for (UUID room : jdbc.queryForList("SELECT id FROM room WHERE room_number LIKE 'DEMO-%'", UUID.class)) {
            periodsByRoom.put(room, periods(jdbc, room));
        }
        Set<String> occupied = new HashSet<>();
        Map<LocalDate, Integer> occupiedPerDate = new HashMap<>();
        for (Map<String, Object> assignment : jdbc.queryForList(
                "SELECT room_id, assigned_from, assigned_to FROM stay_room_assignment")) {
            UUID room = (UUID) assignment.get("room_id");
            LocalDate from = date((Timestamp) assignment.get("assigned_from"));
            LocalDate to = assignment.get("assigned_to") == null ? today : date((Timestamp) assignment.get("assigned_to"));
            if (to.isAfter(today)) {
                to = today;
            }
            for (LocalDate night = from; night.isBefore(to); night = night.plusDays(1)) {
                assertTrue(occupied.add(room + "@" + night), "room occupied twice for " + night);
                assertTrue(sellable(periodsByRoom.get(room), night, today), "occupied but not sellable: " + night);
                occupiedPerDate.merge(night, 1, Integer::sum);
            }
        }
        assertFalse(occupied.isEmpty());
        for (Map.Entry<LocalDate, Integer> entry : occupiedPerDate.entrySet()) {
            long sellableRooms = periodsByRoom.values().stream()
                    .filter(periods -> sellable(periods, entry.getKey(), today))
                    .count();
            assertTrue(entry.getValue() <= sellableRooms, "occupied exceeds sellable on " + entry.getKey());
        }
    }

    /** Confirms re-running the seeder on a seeded database duplicates nothing. */
    @Test
    void shouldBeIdempotentOnRepeatedSeeding() {
        JdbcTemplate jdbc = seededDatabase();
        int assignments = count(jdbc, "SELECT COUNT(*) FROM stay_room_assignment");
        int periods = count(jdbc, "SELECT COUNT(*) FROM room_inventory_period");

        new DemoDataSeeder().seed(jdbc, CLOCK);

        assertEquals(assignments, count(jdbc, "SELECT COUNT(*) FROM stay_room_assignment"));
        assertEquals(periods, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period"));
    }

    /**
     * Confirms an existing demo database (demo marker present, migration BOOTSTRAP baseline, no assignments) is
     * brought to the consistent state, and that a non-demo Room's BOOTSTRAP baseline is never touched.
     */
    @Test
    void shouldBackfillExistingDemoDatabaseWithoutTouchingRealRooms() {
        JdbcTemplate jdbc = seededDatabase();
        UUID realType = jdbc.queryForObject("SELECT id FROM room_type LIMIT 1", UUID.class);
        UUID realRoom = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO room (id, room_number, room_type_id, floor, status, active, created_at, updated_at) "
                        + "VALUES (?, 'REAL-1', ?, '1', 'AVAILABLE', TRUE, now(), now())", realRoom, realType);
        jdbc.update(
                "INSERT INTO room_inventory_period (id, room_id, room_type_id, unavailable_reason, origin, "
                        + "effective_from, effective_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'BOOTSTRAP', now(), NULL, now(), now())",
                UUID.randomUUID(), realRoom, realType);
        int expectedAssignments = count(jdbc, "SELECT COUNT(*) FROM stay_room_assignment");
        int expectedPeriods = count(jdbc, "SELECT COUNT(*) FROM room_inventory_period WHERE room_id <> '" + realRoom + "'");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM room_inventory_period WHERE room_id IN (SELECT id FROM room WHERE room_number LIKE 'DEMO-%')");
        jdbc.update(
                "INSERT INTO room_inventory_period (id, room_id, room_type_id, unavailable_reason, origin, "
                        + "effective_from, effective_to, created_at, updated_at) "
                        + "SELECT gen_random_uuid(), r.id, r.room_type_id, NULL, 'BOOTSTRAP', now(), NULL, now(), now() "
                        + "FROM room r WHERE r.room_number LIKE 'DEMO-%'");

        new DemoDataSeeder().seed(jdbc, CLOCK);
        new DemoDataSeeder().seed(jdbc, CLOCK);

        assertEquals(expectedAssignments, count(jdbc, "SELECT COUNT(*) FROM stay_room_assignment"));
        assertEquals(expectedPeriods,
                count(jdbc, "SELECT COUNT(*) FROM room_inventory_period WHERE room_id <> '" + realRoom + "'"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period p JOIN room r ON r.id = p.room_id "
                + "WHERE r.room_number LIKE 'DEMO-%' AND p.origin = 'BOOTSTRAP'"));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM room_inventory_period WHERE room_id = '" + realRoom
                + "' AND origin = 'BOOTSTRAP' AND effective_to IS NULL"));
    }

    /** Confirms the seeder, and therefore all synthetic history, is registered only for the dev profile. */
    @Test
    void shouldRestrictDemoSeederToDevProfile() {
        Profile profile = DemoDataSeeder.class.getAnnotation(Profile.class);

        assertNotNull(profile);
        assertEquals(List.of("dev"), List.of(profile.value()));
    }

    private boolean sellable(List<Map<String, Object>> periods, LocalDate night, LocalDate today) {
        List<Boolean> covering = new ArrayList<>();
        for (Map<String, Object> period : periods) {
            LocalDate from = date((Timestamp) period.get("effective_from"));
            LocalDate to = period.get("effective_to") == null ? today.plusYears(100) : date((Timestamp) period.get("effective_to"));
            if (!night.isBefore(from) && night.isBefore(to)) {
                covering.add(period.get("unavailable_reason") == null);
            }
        }
        return covering.size() == 1 && covering.get(0);
    }

    private LocalDate date(Timestamp timestamp) {
        return LocalDate.ofInstant(timestamp.toInstant(), ZONE);
    }

    private List<Map<String, Object>> periods(JdbcTemplate jdbc, UUID room) {
        return jdbc.queryForList(
                "SELECT unavailable_reason, origin, effective_from, effective_to FROM room_inventory_period "
                        + "WHERE room_id = ? ORDER BY effective_from", room);
    }

    private JdbcTemplate seededDatabase() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new DemoDataSeeder().seed(jdbc, CLOCK);
        return jdbc;
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
