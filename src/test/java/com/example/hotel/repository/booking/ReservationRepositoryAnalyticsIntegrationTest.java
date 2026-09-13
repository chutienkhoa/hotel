package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.entity.booking.BookingSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies Dashboard Analytics v2 Reservation aggregates against PostgreSQL mappings. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationRepositoryAnalyticsIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE_ROOM_TYPE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Supplies Testcontainers database connection properties. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Inserts planned reservations inside and outside the 2026 Analytics v2 reporting period. */
    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        UUID singleRoomId = UUID.randomUUID();
        UUID doubleRoomId = UUID.randomUUID();

        insertUser(userId);
        insertGuest(guestId, userId);
        insertRoom(singleRoomId, SINGLE_ROOM_TYPE_ID, "A101", userId);
        insertRoom(doubleRoomId, DOUBLE_ROOM_TYPE_ID, "A201", userId);

        UUID januaryReservationId = UUID.randomUUID();
        UUID juneReservationId = UUID.randomUUID();
        UUID priorYearReservationId = UUID.randomUUID();
        insertReservation(
                januaryReservationId,
                guestId,
                userId,
                "R20260115-000001",
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 1, 15));
        insertReservation(
                juneReservationId,
                guestId,
                userId,
                "R20260610-000001",
                BookingSource.AGODA,
                "analytics-agoda-2026",
                LocalDate.of(2026, 6, 10));
        insertReservation(
                priorYearReservationId,
                guestId,
                userId,
                "R20251210-000001",
                BookingSource.AIRBNB,
                "analytics-airbnb-2025",
                LocalDate.of(2025, 12, 10));

        insertReservationRoom(januaryReservationId, singleRoomId, userId, LocalDate.of(2026, 1, 15));
        insertReservationRoom(januaryReservationId, doubleRoomId, userId, LocalDate.of(2026, 1, 15));
        insertReservationRoom(juneReservationId, doubleRoomId, userId, LocalDate.of(2026, 6, 10));
        insertReservationRoom(priorYearReservationId, singleRoomId, userId, LocalDate.of(2025, 12, 10));
    }

    /** Verifies the Analytics v2 repository queries use check-in dates and count room rows. */
    @Test
    @Transactional
    void shouldAggregateCurrentYearAnalyticsUsingPlannedCheckInDates() {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDateExclusive = LocalDate.of(2027, 1, 1);

        assertEquals(
                List.of(List.of(2026, 1, 1L), List.of(2026, 6, 1L)),
                reservationRepository.countByCheckInMonthWithin(startDate, endDateExclusive).stream()
                        .map(row -> List.of(
                                ((Number) row[0]).intValue(),
                                ((Number) row[1]).intValue(),
                                ((Number) row[2]).longValue()))
                        .toList());
        assertEquals(
                List.of(List.of("DOUBLE", "Double Room", 2L), List.of("SINGLE", "Single Room", 1L)),
                reservationRepository.countBookedRoomsByRoomTypeWithin(startDate, endDateExclusive).stream()
                        .map(row -> List.of((String) row[0], (String) row[1], ((Number) row[2]).longValue()))
                        .toList());
        assertEquals(
                List.of(List.of(BookingSource.AGODA, 1L), List.of(BookingSource.DIRECT, 1L)),
                reservationRepository.countBySourceWithin(startDate, endDateExclusive).stream()
                        .map(row -> List.of((BookingSource) row[0], ((Number) row[1]).longValue()))
                        .toList());
    }

    /** Inserts one authenticated audit user required by reservation fixture rows. */
    private void insertUser(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId,
                "analytics-" + userId,
                "not-used-in-test",
                Instant.now(),
                Instant.now());
    }

    /** Inserts one guest required by reservation fixture rows. */
    private void insertGuest(UUID guestId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                guestId,
                "G" + guestId.toString().substring(0, 8),
                Instant.now(),
                userId,
                Instant.now(),
                userId);
    }

    /** Inserts one operational Room with an approved RoomType reference record. */
    private void insertRoom(UUID roomId, UUID roomTypeId, String roomNumber, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, ?, 'AVAILABLE', TRUE, ?, ?, ?, ?)",
                roomId,
                roomNumber,
                roomTypeId,
                Instant.now(),
                userId,
                Instant.now(),
                userId);
    }

    /** Inserts one Reservation with a planned check-in date and approved source. */
    private void insertReservation(
            UUID reservationId,
            UUID guestId,
            UUID userId,
            String reservationNumber,
            BookingSource source,
            String externalBookingId,
            LocalDate checkInDate) {
        jdbcTemplate.update(
                "INSERT INTO reservation (id, reservation_number, guest_id, source, external_booking_id, "
                        + "status, reserved_at, check_in_date, check_out_date, currency, total_amount, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, 'VND', 1, ?, ?, ?, ?)",
                reservationId,
                reservationNumber,
                guestId,
                source.name(),
                externalBookingId,
                Instant.now(),
                checkInDate,
                checkInDate.plusDays(1),
                Instant.now(),
                userId,
                Instant.now(),
                userId);
    }

    /** Inserts one assigned-room row for a Reservation fixture. */
    private void insertReservationRoom(
            UUID reservationId, UUID roomId, UUID userId, LocalDate checkInDate) {
        jdbcTemplate.update(
                "INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, "
                        + "nightly_rate, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?)",
                UUID.randomUUID(),
                reservationId,
                roomId,
                checkInDate,
                checkInDate.plusDays(1),
                Instant.now(),
                userId,
                Instant.now(),
                userId);
    }
}
