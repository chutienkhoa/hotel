package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Verifies migration V30: pre-existing Reservations are backfilled to 1 adult and 0 children, the columns are NOT
 * NULL, and the database rejects invalid counts. Drives Flyway directly so it can migrate to V29, insert a
 * Reservation using the old schema, then migrate to the latest version.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReservationGuestCompositionMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms a Reservation that pre-dates V30 gets 1 adult and 0 children. */
    @Test
    void shouldBackfillExistingReservationsToOneAdultAndNoChildren() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, "29");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);
        UUID legacy = insertLegacyReservation(jdbc, user, guest);

        migrate(dataSource, null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT adult_count, child_count FROM reservation WHERE id = ?", legacy);
        assertEquals(1, row.get("adult_count"));
        assertEquals(0, row.get("child_count"));
    }

    /** Confirms the constraints accept valid counts and reject zero adults, negative children and nulls. */
    @Test
    void shouldEnforceCountConstraints() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);

        insertReservation(jdbc, user, guest, "1", "0");
        insertReservation(jdbc, user, guest, "3", "2");

        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(jdbc, user, guest, "0", "0"));
        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(jdbc, user, guest, "-1", "0"));
        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(jdbc, user, guest, "1", "-1"));
        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(jdbc, user, guest, "NULL", "0"));
        assertThrows(DataIntegrityViolationException.class, () -> insertReservation(jdbc, user, guest, "1", "NULL"));
    }

    /** Confirms V31 leaves pre-existing Reservations valid with zero accompanying guests and enforces its constraints. */
    @Test
    void shouldCreateReservationGuestTableWithConstraintsAndKeepExistingReservationsValid() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, "30");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);
        UUID other = insertGuest(jdbc, user);
        UUID legacy = insertLegacyReservation(jdbc, user, guest);

        migrate(dataSource, null);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ?", Integer.class, legacy));
        insertAccompanying(jdbc, user, legacy, other);
        assertThrows(DataIntegrityViolationException.class, () -> insertAccompanying(jdbc, user, legacy, other));
        assertThrows(DataIntegrityViolationException.class, () -> insertAccompanying(jdbc, user, UUID.randomUUID(), other));
        assertThrows(DataIntegrityViolationException.class, () -> insertAccompanying(jdbc, user, legacy, UUID.randomUUID()));
    }

    private void insertAccompanying(JdbcTemplate jdbc, UUID user, UUID reservation, UUID guest) {
        jdbc.update("INSERT INTO reservation_guest (id, reservation_id, guest_id, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, now(), ?, now(), ?)", UUID.randomUUID(), reservation, guest, user, user);
    }

    private void insertReservation(JdbcTemplate jdbc, UUID user, UUID guest, String adults, String children) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, adult_count, child_count, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'DRAFT', now(), ?, ?, 'VND', 1, " + adults + ", " + children
                        + ", now(), ?, now(), ?)",
                id, "GC" + id.toString().substring(0, 12), guest, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), user, user);
    }

    private UUID insertLegacyReservation(JdbcTemplate jdbc, UUID user, UUID guest) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, external_booking_id, status, "
                        + "reserved_at, check_in_date, check_out_date, currency, total_amount, notes, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', NULL, 'CONFIRMED', ?, ?, ?, 'VND', ?, NULL, ?, ?, ?, ?)",
                id, "LG" + id.toString().substring(0, 12), guest, now(), LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11),
                new BigDecimal("100.000000"), now(), user, now(), user);
        return id;
    }

    /** Gives each test an empty schema so it controls exactly which migrations have been applied. */
    private void resetSchema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    private static java.sql.Timestamp now() {
        return java.sql.Timestamp.from(Instant.now());
    }

    private DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private void migrate(DataSource dataSource, String targetVersion) {
        var configuration = Flyway.configure().dataSource(dataSource);
        if (targetVersion != null) {
            configuration = configuration.target(targetVersion);
        }
        configuration.load().migrate();
    }

    private UUID insertUser(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'not-used-in-test', TRUE, now(), now())", id, "gc-migration-" + id);
        return id;
    }

    private UUID insertGuest(JdbcTemplate jdbc, UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, now(), ?, now(), ?)", id, "G" + id.toString().substring(0, 8), user, user);
        return id;
    }
}
