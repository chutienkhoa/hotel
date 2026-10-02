package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Dashboard Currently Staying "in-house at an instant" guest-headcount query against real
 * PostgreSQL timestamptz semantics. This is the query the Final Dependency Audit concluded makes the
 * "vs yesterday" trend exactly derivable without any snapshot table: it must get the interval-containment
 * boundary exactly right for a stay that checks out, or checks in, on the very day being compared.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StayRepositoryGuestHeadcountIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StayRepository stayRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID guestId;

    /** Supplies Testcontainers database connection properties. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        guestId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, 'not-used-in-test', TRUE, now(), now())",
                userId, "guest-headcount-" + userId);
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, now(), ?, now(), ?)",
                guestId, "G" + guestId.toString().substring(0, 8), userId, userId);
    }

    /**
     * Verifies every boundary case the Dashboard's "vs yesterday" trend depends on: a stay still in-house
     * is counted; a stay that checks out AFTER the evaluation instant is still counted (a guest checked
     * out "today" was still in-house at the start of today); a stay checked out ON OR BEFORE the instant
     * is excluded; and a stay checked in AFTER the instant is excluded (a guest who checks in "today" was
     * not in-house at the start of today).
     */
    @Test
    void shouldSumGuestHeadcountForStaysInHouseAtTheEvaluationInstant() {
        Instant evaluationInstant = Instant.parse("2026-09-16T00:00:00Z");

        insertStay(2, 1, Instant.parse("2026-09-14T10:00:00Z"), null);
        insertStay(1, 0, Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-16T11:00:00Z"));
        insertStay(4, 0, Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-15T09:00:00Z"));
        insertStay(2, 0, Instant.parse("2026-09-16T12:00:00Z"), null);

        long headcount = stayRepository.sumGuestHeadcountInHouseAt(evaluationInstant);

        assertEquals(3L + 1L, headcount);
    }

    /** Verifies zero is returned, not null or an exception, when nobody was in-house at the instant. */
    @Test
    void shouldReturnZeroWhenNobodyWasInHouseAtTheInstant() {
        Instant evaluationInstant = Instant.parse("2026-09-16T00:00:00Z");

        insertStay(2, 0, Instant.parse("2026-09-10T10:00:00Z"), Instant.parse("2026-09-15T09:00:00Z"));

        assertEquals(0L, stayRepository.sumGuestHeadcountInHouseAt(evaluationInstant));
    }

    private void insertStay(int adults, int children, Instant actualCheckInAt, Instant actualCheckOutAt) {
        UUID reservationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reservation (id, reservation_number, guest_id, source, adult_count, child_count, status, "
                        + "reserved_at, check_in_date, check_out_date, currency, total_amount, created_at, created_by, "
                        + "updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, ?, 'CHECKED_IN', now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                reservationId,
                "RHC" + reservationId.toString().substring(0, 10),
                guestId,
                adults,
                children,
                LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 20),
                userId,
                userId);
        UUID stayId = UUID.randomUUID();
        String status = actualCheckOutAt == null ? "CHECKED_IN" : "CHECKED_OUT";
        jdbcTemplate.update(
                "INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?)",
                stayId,
                reservationId,
                status,
                Timestamp.from(actualCheckInAt),
                actualCheckOutAt == null ? null : Timestamp.from(actualCheckOutAt),
                userId,
                userId);
    }
}
