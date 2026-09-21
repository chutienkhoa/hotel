package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the V13 Payment currency migration safely backfills Payment rows that existed before
 * {@code currency}/{@code exchange_rate}/{@code applied_amount} were added, using each Payment's
 * own Reservation currency (payment -&gt; stay -&gt; reservation.currency) rather than assuming VND.
 *
 * <p>This test drives Flyway directly (not through Spring Boot's auto-configuration) so it can
 * migrate to V12, insert Payment rows using the pre-V13 schema, and then migrate to the latest
 * version to observe the backfill.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PaymentCurrencyMigrationBackfillTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms a pre-existing VND-Reservation Payment backfills as VND with applied_amount = amount. */
    @Test
    void shouldBackfillVndReservationPaymentAsVnd() {
        DataSource dataSource = dataSource();
        migrate(dataSource, "12");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID userId = insertUser(jdbcTemplate);
        UUID guestId = insertGuest(jdbcTemplate, userId);
        UUID reservationId = insertReservation(jdbcTemplate, userId, guestId, "VND");
        UUID stayId = insertStay(jdbcTemplate, userId, reservationId);
        UUID paymentId = insertLegacyPayment(jdbcTemplate, userId, stayId, new BigDecimal("3000000.000000"));

        migrate(dataSource, null);

        assertBackfilled(jdbcTemplate, paymentId, "VND", new BigDecimal("3000000.000000"));
    }

    /** Confirms a pre-existing USD-Reservation Payment backfills as USD, not VND. */
    @Test
    void shouldBackfillUsdReservationPaymentAsUsd() {
        DataSource dataSource = dataSource();
        migrate(dataSource, "12");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID userId = insertUser(jdbcTemplate);
        UUID guestId = insertGuest(jdbcTemplate, userId);
        UUID reservationId = insertReservation(jdbcTemplate, userId, guestId, "USD");
        UUID stayId = insertStay(jdbcTemplate, userId, reservationId);
        UUID paymentId = insertLegacyPayment(jdbcTemplate, userId, stayId, new BigDecimal("120.000000"));

        migrate(dataSource, null);

        assertBackfilled(jdbcTemplate, paymentId, "USD", new BigDecimal("120.000000"));
    }

    /** Asserts one Payment row backfilled to the expected currency, applied_amount, and null exchange_rate. */
    private void assertBackfilled(
            JdbcTemplate jdbcTemplate, UUID paymentId, String expectedCurrency, BigDecimal expectedAppliedAmount) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT currency, applied_amount, exchange_rate FROM payment WHERE id = ?", paymentId);

        assertEquals(expectedCurrency, row.get("currency"));
        assertEquals(0, expectedAppliedAmount.compareTo((BigDecimal) row.get("applied_amount")));
        assertNull(row.get("exchange_rate"));
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

    /**
     * Runs Flyway against the supplied data source up to one target version.
     *
     * @param dataSource database to migrate
     * @param targetVersion Flyway target version, or {@code null} to migrate to the latest version
     */
    private void migrate(DataSource dataSource, String targetVersion) {
        var configuration = Flyway.configure().dataSource(dataSource);
        if (targetVersion != null) {
            configuration = configuration.target(targetVersion);
        }
        configuration.load().migrate();
    }

    /** Inserts one authenticated audit user required by fixture rows. */
    private UUID insertUser(JdbcTemplate jdbcTemplate) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId, "migration-test-" + userId, "not-used-in-test", Instant.now(), Instant.now());
        return userId;
    }

    /** Inserts one Guest required by a Reservation fixture row. */
    private UUID insertGuest(JdbcTemplate jdbcTemplate, UUID userId) {
        UUID guestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                guestId, "G" + guestId.toString().substring(0, 8), Instant.now(), userId, Instant.now(), userId);
        return guestId;
    }

    /** Inserts one Reservation with the given currency, pre-dating the V13 Payment currency migration. */
    private UUID insertReservation(JdbcTemplate jdbcTemplate, UUID userId, UUID guestId, String currency) {
        UUID reservationId = UUID.randomUUID();
        LocalDate checkIn = LocalDate.of(2026, 1, 10);
        jdbcTemplate.update(
                "INSERT INTO reservation (id, reservation_number, guest_id, source, external_booking_id, "
                        + "status, reserved_at, check_in_date, check_out_date, currency, total_amount, notes, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', NULL, 'CHECKED_IN', ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)",
                reservationId,
                "R-BACKFILL-" + reservationId,
                guestId,
                Instant.now(),
                checkIn,
                checkIn.plusDays(1),
                currency,
                new BigDecimal("100.000000"),
                Instant.now(),
                userId,
                Instant.now(),
                userId);
        return reservationId;
    }

    /** Inserts one checked-in Stay for a Reservation fixture row. */
    private UUID insertStay(JdbcTemplate jdbcTemplate, UUID userId, UUID reservationId) {
        UUID stayId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'CHECKED_IN', ?, NULL, ?, ?, ?, ?)",
                stayId, reservationId, Instant.now(), Instant.now(), userId, Instant.now(), userId);
        return stayId;
    }

    /** Inserts one Payment row using the pre-V13 schema (no currency/exchange_rate/applied_amount). */
    private UUID insertLegacyPayment(JdbcTemplate jdbcTemplate, UUID userId, UUID stayId, BigDecimal amount) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment (id, stay_id, amount, method, status, paid_at, reference, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'CASH', 'PENDING', NULL, NULL, ?, ?, ?, ?)",
                paymentId, stayId, amount, Instant.now(), userId, Instant.now(), userId);
        return paymentId;
    }
}
