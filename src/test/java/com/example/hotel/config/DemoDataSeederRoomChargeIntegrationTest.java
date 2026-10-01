package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Verifies the dev seeder creates the automatic ROOM Charge data that
 * {@code ReservationService.checkIn()} would have created for every seeded CHECKED_IN/CHECKED_OUT
 * Stay, since seeded Stays are inserted directly and bypass that production check-in flow.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederRoomChargeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms exactly one ROOM Charge is seeded per seeded ReservationRoom, matching its total-amount snapshot. */
    @Test
    void shouldSeedOneRoomChargePerReservationRoomMatchingTotalAmountSnapshot() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        new DemoDataSeeder().seed(jdbcTemplate, clock);

        Integer stayCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stay WHERE status IN ('CHECKED_IN', 'CHECKED_OUT')", Integer.class);
        Integer roomChargeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge WHERE type = 'ROOM'", Integer.class);
        assertTrue(stayCount != null && stayCount > 0);
        assertEquals(stayCount, roomChargeCount);

        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                "SELECT s.id AS stay_id, rr.total_amount AS expected_amount, c.amount AS charge_amount, "
                        + "c.description AS description "
                        + "FROM stay s "
                        + "JOIN reservation_room rr ON rr.reservation_id = s.reservation_id "
                        + "LEFT JOIN charge c ON c.stay_id = s.id AND c.type = 'ROOM' "
                        + "WHERE s.status IN ('CHECKED_IN', 'CHECKED_OUT') "
                        + "AND (c.id IS NULL OR c.amount <> rr.total_amount)");
        assertTrue(mismatches.isEmpty(), "Seeded ROOM Charge amount must equal ReservationRoom.totalAmount: "
                + mismatches);

        List<Map<String, Object>> unlinked = jdbcTemplate.queryForList(
                "SELECT rr.id FROM stay s JOIN reservation_room rr ON rr.reservation_id = s.reservation_id "
                        + "LEFT JOIN charge c ON c.source_reservation_room_id = rr.id AND c.stay_id = s.id AND c.type = 'ROOM' "
                        + "WHERE s.status IN ('CHECKED_IN', 'CHECKED_OUT') AND c.id IS NULL");
        assertTrue(unlinked.isEmpty(), "Every seeded original ROOM Charge must link to its ReservationRoom: " + unlinked);
        assertEquals(roomChargeCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge WHERE type = 'ROOM' AND source_reservation_room_id IS NOT NULL", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge WHERE type <> 'ROOM' AND source_reservation_room_id IS NOT NULL", Integer.class));

        List<Map<String, Object>> descriptions = jdbcTemplate.queryForList(
                "SELECT description FROM charge WHERE type = 'ROOM'");
        for (Map<String, Object> row : descriptions) {
            assertTrue(String.valueOf(row.get("description")).startsWith("Room "));
        }
    }

    /** Confirms re-running the seeder does not create duplicate ROOM Charges. */
    @Test
    void shouldNotDuplicateRoomChargesOnRepeatedSeeding() {
        DataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        DemoDataSeeder seeder = new DemoDataSeeder();

        seeder.seed(jdbcTemplate, clock);
        Integer firstRunCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge WHERE type = 'ROOM'", Integer.class);

        seeder.seed(jdbcTemplate, clock);
        Integer secondRunCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge WHERE type = 'ROOM'", Integer.class);

        assertEquals(firstRunCount, secondRunCount);
    }

    /** Builds a plain JDBC data source for the Testcontainers PostgreSQL instance. */
    private DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
