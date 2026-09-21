package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Verifies migration V34: every existing Payment is backfilled with its Stay's Reservation, reservation_id becomes NOT
 * NULL, stay_id accepts NULL for prepayments, and the composite stay/reservation and status constraints hold.
 */
@Testcontainers(disabledWithoutDocker = true)
class PaymentReservationMigrationTest {

    private static final UUID TYPE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms existing Payments backfill losslessly and the new columns and constraints behave as approved. */
    @Test
    void shouldBackfillReservationOwnershipAndEnforceThePrepaymentConstraints() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, "33");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);
        UUID[] legacy = reservationWithStay(jdbc, user, guest);
        UUID legacyPayment = payment(jdbc, user, legacy[1], "PAID");
        UUID legacyPending = payment(jdbc, user, legacy[1], "PENDING");

        migrate(dataSource, null);

        assertEquals(legacy[0], jdbc.queryForObject("SELECT reservation_id FROM payment WHERE id = ?", UUID.class, legacyPayment));
        assertEquals(legacy[0], jdbc.queryForObject("SELECT reservation_id FROM payment WHERE id = ?", UUID.class, legacyPending));
        assertEquals(legacy[1], jdbc.queryForObject("SELECT stay_id FROM payment WHERE id = ?", UUID.class, legacyPayment));
        assertEquals("NO", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'reservation_id'", String.class));
        assertEquals("YES", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'payment' AND column_name = 'stay_id'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'idx_payment_reservation'", Integer.class));

        UUID confirmed = confirmedReservation(jdbc, user, guest);
        insert(jdbc, user, confirmed, null, "PAID");
        insert(jdbc, user, confirmed, null, "REFUNDED");
        assertThrows(DataIntegrityViolationException.class, () -> insert(jdbc, user, confirmed, null, "PENDING"), "prepayment is never PENDING");
        assertThrows(DataIntegrityViolationException.class, () -> insert(jdbc, user, confirmed, null, "FAILED"), "prepayment is never FAILED");
        assertThrows(DataIntegrityViolationException.class, () -> insert(jdbc, user, null, null, "PAID"), "reservation is required");
        assertThrows(DataIntegrityViolationException.class, () -> insert(jdbc, user, confirmed, legacy[1], "PAID"), "the Stay must belong to the Payment's Reservation");
        insert(jdbc, user, legacy[0], legacy[1], "PAID");
    }

    private UUID[] reservationWithStay(JdbcTemplate jdbc, UUID user, UUID guest) {
        UUID reservation = confirmedReservation(jdbc, user, guest);
        UUID stay = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', now(), now(), ?, now(), ?)", stay, reservation, user, user);
        return new UUID[] {reservation, stay};
    }

    private UUID confirmedReservation(JdbcTemplate jdbc, UUID user, UUID guest) {
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, check_out_date, "
                + "currency, total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, "
                + "'VND', 1, now(), ?, now(), ?)", reservation, "PM" + reservation.toString().substring(0, 12), guest,
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11), user, user);
        return reservation;
    }

    private UUID payment(JdbcTemplate jdbc, UUID user, UUID stay, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment (id, stay_id, amount, method, status, paid_at, currency, applied_amount, created_at, created_by, "
                + "updated_at, updated_by) VALUES (?, ?, 10, 'CASH', ?, now(), 'VND', 10, now(), ?, now(), ?)", id, stay, status, user, user);
        return id;
    }

    private void insert(JdbcTemplate jdbc, UUID user, UUID reservation, UUID stay, String status) {
        jdbc.update("INSERT INTO payment (id, reservation_id, stay_id, amount, method, status, paid_at, currency, applied_amount, created_at, "
                + "created_by, updated_at, updated_by, refund_reason) VALUES (?, ?, ?, 10, 'CASH', ?, now(), 'VND', 10, now(), ?, now(), ?, "
                + "CASE WHEN ? = 'REFUNDED' THEN 'why' END)",
                UUID.randomUUID(), reservation, stay, status, user, user, status);
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
