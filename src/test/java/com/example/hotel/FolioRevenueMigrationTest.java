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
 * Verifies migration V33: the safe legacy backfill of original ROOM charge links (only unambiguous rows), the source and
 * uniqueness constraints, the Charge link of Additional Revenue with its nullable payment method, and the seeded
 * system categories.
 */
@Testcontainers(disabledWithoutDocker = true)
class FolioRevenueMigrationTest {

    private static final UUID TYPE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Confirms only unambiguous legacy check-in ROOM charges are linked; the rest stay NULL and nothing is altered. */
    @Test
    void shouldBackfillOnlyUnambiguousLegacyRoomCharges() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, "32");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);

        UUID okStay = stay(jdbc, user, guest);
        UUID okLine = line(jdbc, user, okStay, "MB-1", "2000000");
        UUID okCharge = charge(jdbc, user, okStay, "Room MB-1", "2000000");

        UUID dupStay = stay(jdbc, user, guest);
        UUID dupLine = line(jdbc, user, dupStay, "MB-2", "1000000");
        UUID dupA = charge(jdbc, user, dupStay, "Room MB-2", "1000000");
        UUID dupB = charge(jdbc, user, dupStay, "Room MB-2", "1000000");

        UUID amountStay = stay(jdbc, user, guest);
        line(jdbc, user, amountStay, "MB-3", "1000000");
        UUID wrongAmount = charge(jdbc, user, amountStay, "Room MB-3", "999");

        UUID serviceStay = stay(jdbc, user, guest);
        line(jdbc, user, serviceStay, "MB-4", "1000000");
        UUID minibar = chargeOfType(jdbc, user, serviceStay, "MINIBAR", "Room MB-4", "1000000");

        UUID extStay = stay(jdbc, user, guest);
        UUID extLine = line(jdbc, user, extStay, "MB-5", "1000000");
        UUID extCharge = charge(jdbc, user, extStay, "Room MB-5", "1000000");
        UUID extension = UUID.randomUUID();
        jdbc.update("INSERT INTO stay_extension (id, stay_id, sequence_no, previous_check_out_date, new_check_out_date, created_at, created_by, "
                + "updated_at, updated_by) VALUES (?, ?, 1, ?, ?, now(), ?, now(), ?)", extension, extStay,
                LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 12), user, user);
        jdbc.update("INSERT INTO stay_extension_room (id, stay_extension_id, original_reservation_room_id, room_id, from_date, to_date, "
                + "nightly_rate, amount, charge_id, created_at, created_by, updated_at, updated_by) "
                + "SELECT ?, ?, ?, room_id, ?, ?, 1000000, 1000000, ?, now(), ?, now(), ? FROM reservation_room WHERE id = ?",
                UUID.randomUUID(), extension, extLine, LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 12), extCharge, user, user, extLine);

        migrate(dataSource, null);

        assertEquals(okLine, source(jdbc, okCharge));
        assertNull(source(jdbc, dupA), "two identical charges for one booked room are ambiguous");
        assertNull(source(jdbc, dupB));
        assertNull(source(jdbc, wrongAmount), "amount differs");
        assertNull(source(jdbc, minibar), "only ROOM charges are linked");
        assertNull(source(jdbc, extCharge), "extension charges are never linked");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charge WHERE source_reservation_room_id IS NOT NULL", Integer.class));
        assertEquals(0, new BigDecimal("999").compareTo(jdbc.queryForObject("SELECT amount FROM charge WHERE id = ?", BigDecimal.class, wrongAmount)));
        assertNotNull(dupLine);
    }

    /** Confirms the source uniqueness, ROOM-only source, revenue link and payment-method constraints and the seeded categories. */
    @Test
    void shouldEnforceTheNewConstraintsAndSeedTheSystemCategories() {
        DataSource dataSource = dataSource();
        resetSchema(dataSource);
        migrate(dataSource, null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = insertUser(jdbc);
        UUID guest = insertGuest(jdbc, user);
        UUID stay = stay(jdbc, user, guest);
        UUID line = line(jdbc, user, stay, "MC-1", "1000000");

        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM additional_revenue_category WHERE code LIKE 'GUEST_%'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM additional_revenue_category WHERE code IN ('OTHER', 'ELECTRIC_CART_RENTAL')", Integer.class),
                "existing standalone categories are untouched");

        insertLinked(jdbc, user, stay, "ROOM", line);
        assertThrows(DataIntegrityViolationException.class, () -> insertLinked(jdbc, user, stay, "ROOM", line), "one original charge per ReservationRoom");
        assertThrows(DataIntegrityViolationException.class, () -> insertLinked(jdbc, user, stay, "SERVICE", line), "only ROOM charges may have a source");
        assertThrows(DataIntegrityViolationException.class, () -> insertLinked(jdbc, user, stay, "ROOM", UUID.randomUUID()), "source FK");

        UUID category = jdbc.queryForObject("SELECT id FROM additional_revenue_category WHERE code = 'GUEST_SERVICE'", UUID.class);
        UUID serviceCharge = chargeOfType(jdbc, user, stay, "SERVICE", "x", "10");
        revenue(jdbc, user, category, serviceCharge, null);
        assertThrows(DataIntegrityViolationException.class, () -> revenue(jdbc, user, category, serviceCharge, null), "one revenue per charge");
        assertThrows(DataIntegrityViolationException.class, () -> revenue(jdbc, user, category, UUID.randomUUID(), null), "charge FK");
        assertThrows(DataIntegrityViolationException.class, () -> revenue(jdbc, user, category, null, null), "standalone revenue needs a payment method");
        revenue(jdbc, user, category, null, "CASH");
        assertThrows(DataIntegrityViolationException.class, () -> revenue(jdbc, user, category, null, "BITCOIN"));
    }

    private UUID source(JdbcTemplate jdbc, UUID charge) {
        return jdbc.queryForObject("SELECT source_reservation_room_id FROM charge WHERE id = ?", UUID.class, charge);
    }

    private UUID stay(JdbcTemplate jdbc, UUID user, UUID guest) {
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, check_out_date, "
                + "currency, total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 'DIRECT', 'CHECKED_IN', now(), ?, ?, "
                + "'VND', 1, now(), ?, now(), ?)", reservation, "MG" + reservation.toString().substring(0, 12), guest,
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11), user, user);
        UUID stay = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', now(), now(), ?, now(), ?)", stay, reservation, user, user);
        return stay;
    }

    private UUID line(JdbcTemplate jdbc, UUID user, UUID stay, String roomNumber, String total) {
        UUID room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'OCCUPIED', TRUE, now(), ?, now(), ?)", room, roomNumber, TYPE, user, user);
        UUID line = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, total_amount, "
                + "created_at, created_by, updated_at, updated_by) SELECT ?, s.reservation_id, ?, ?, ?, ?, ?, now(), ?, now(), ? "
                + "FROM stay s WHERE s.id = ?", line, room, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11),
                new BigDecimal(total), new BigDecimal(total), user, user, stay);
        return line;
    }

    private UUID charge(JdbcTemplate jdbc, UUID user, UUID stay, String description, String amount) {
        return chargeOfType(jdbc, user, stay, "ROOM", description, amount);
    }

    private UUID chargeOfType(JdbcTemplate jdbc, UUID user, UUID stay, String type, String description, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, amount, charged_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, now(), now(), ?, now(), ?)", id, stay, type, description, new BigDecimal(amount), user, user);
        return id;
    }

    private void insertLinked(JdbcTemplate jdbc, UUID user, UUID stay, String type, UUID source) {
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, amount, charged_at, created_at, created_by, updated_at, updated_by, "
                + "source_reservation_room_id) VALUES (?, ?, ?, 'x', 10, now(), now(), ?, now(), ?, ?)",
                UUID.randomUUID(), stay, type, user, user, source);
    }

    private void revenue(JdbcTemplate jdbc, UUID user, UUID category, UUID charge, String method) {
        jdbc.update("INSERT INTO additional_revenue (id, category_id, amount, currency, revenue_date, payment_method, status, charge_id, "
                + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, 10, 'VND', ?, ?, 'RECORDED', ?, now(), ?, now(), ?)",
                UUID.randomUUID(), category, LocalDate.of(2026, 1, 10), method, charge, user, user);
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
