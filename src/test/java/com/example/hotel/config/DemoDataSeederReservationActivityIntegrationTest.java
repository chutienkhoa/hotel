package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the dev seeder gives every demo Reservation the audit history that the production flows would have written
 * to reach its seeded status, so no seeded Reservation has an empty Activity Log.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederReservationActivityIntegrationTest {

    private static final String DEMO_NOTES = "Synthetic development demonstration reservation";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZONE);

    /** The audited transitions each seeded status must have gone through, oldest first. */
    private static final Map<String, List<String>> EXPECTED_ACTIONS = Map.of(
            "DRAFT", List.of("CREATE"),
            "CONFIRMED", List.of("CREATE", "CONFIRM"),
            "CANCELLED", List.of("CREATE", "CONFIRM", "CANCEL"),
            "NO_SHOW", List.of("CREATE", "CONFIRM", "NO_SHOW"),
            "CHECKED_IN", List.of("CREATE", "CONFIRM", "CHECK_IN"),
            "CHECKED_OUT", List.of("CREATE", "CONFIRM", "CHECK_IN", "CHECK_OUT"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms each seeded Reservation's history matches its status, ends on that status, and is chronological. */
    @Test
    void shouldSeedActivityHistoryConsistentWithEachReservationStatus() {
        JdbcTemplate jdbc = seededDatabase();

        List<Map<String, Object>> reservations = jdbc.queryForList(
                "SELECT id, status, reserved_at FROM reservation WHERE notes = ?",
                DEMO_NOTES);
        assertFalse(reservations.isEmpty());
        java.util.Set<Object> seenStatuses = new java.util.HashSet<>();
        for (Map<String, Object> reservation : reservations) {
            String status = (String) reservation.get("status");
            seenStatuses.add(status);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT action, old_value, new_value, created_at FROM audit_log "
                            + "WHERE entity_type = 'RESERVATION' AND entity_id = ? ORDER BY created_at, id",
                    reservation.get("id"));

            assertEquals(EXPECTED_ACTIONS.get(status), rows.stream().map(row -> row.get("action")).toList(),
                    "history of a " + status + " reservation");
            assertEquals(status, rows.get(rows.size() - 1).get("new_value"));
            assertEquals(null, rows.get(0).get("old_value"));
            assertEquals("DRAFT", rows.get(0).get("new_value"));
            assertEquals(reservation.get("reserved_at"), rows.get(0).get("created_at"));
            for (int i = 1; i < rows.size(); i++) {
                assertEquals(rows.get(i - 1).get("new_value"), rows.get(i).get("old_value"));
                assertTrue(((Timestamp) rows.get(i).get("created_at"))
                        .after((Timestamp) rows.get(i - 1).get("created_at")), "strictly chronological");
            }
        }
        assertTrue(seenStatuses.containsAll(EXPECTED_ACTIONS.keySet()), "demo data covers every status");
    }

    /** Confirms stay-bearing history uses the Stay's actual check-in and check-out instants. */
    @Test
    void shouldTimeCheckInAndCheckOutFromTheStay() {
        JdbcTemplate jdbc = seededDatabase();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.action AS action, a.created_at AS at, s.actual_check_in_at AS s_in, "
                        + "s.actual_check_out_at AS s_out FROM audit_log a "
                        + "JOIN stay s ON s.reservation_id = a.entity_id "
                        + "WHERE a.entity_type = 'RESERVATION' AND a.action IN ('CHECK_IN', 'CHECK_OUT')");
        assertFalse(rows.isEmpty());
        for (Map<String, Object> row : rows) {
            assertEquals("CHECK_IN".equals(row.get("action")) ? row.get("s_in") : row.get("s_out"), row.get("at"));
        }
    }

    /** Confirms re-seeding adds nothing, and an already-seeded database lacking history is backfilled once. */
    @Test
    void shouldBackfillOnlyReservationsWithoutActivityAndStayIdempotent() {
        JdbcTemplate jdbc = seededDatabase();
        int expected = count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'RESERVATION'");
        assertTrue(expected > 0);

        new DemoDataSeeder().seed(jdbc, CLOCK);
        assertEquals(expected, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'RESERVATION'"));

        jdbc.update("DELETE FROM audit_log WHERE entity_type = 'RESERVATION'");
        new DemoDataSeeder().seed(jdbc, CLOCK);
        new DemoDataSeeder().seed(jdbc, CLOCK);
        assertEquals(expected, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'RESERVATION'"));
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM reservation r WHERE r.notes = '"
                + DEMO_NOTES + "' AND NOT EXISTS (SELECT 1 FROM audit_log a "
                + "WHERE a.entity_type = 'RESERVATION' AND a.entity_id = r.id)"));
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
