package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * Verifies the Monthly Occupancy Report against PostgreSQL: the real assignment, inventory and coverage
 * queries feed the calculation. All months used are completed months in the past.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MonthlyOccupancyReportIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant LONG_AGO = at(2026, 1, 1, 0, 0);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MonthlyOccupancyReportService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private UUID doubleType;
    private UUID familyType;
    private int sequence;

    /**
     * Supplies the Testcontainers PostgreSQL connection to Spring.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Clears occupancy and inventory data and creates the shared audit user, guest and RoomTypes. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM room_inventory_period");
        jdbc.update("DELETE FROM room");
        sequence = 0;
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "occ." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        doubleType = jdbc.queryForObject("SELECT id FROM room_type WHERE code = 'DOUBLE'", UUID.class);
        familyType = jdbc.queryForObject("SELECT id FROM room_type WHERE code = 'FAMILY'", UUID.class);
    }

    /**
     * Confirms a representative month end to end: a cross-month stay, a two-room reservation, an out-of-order
     * period split during a day, per-type totals and the hotel rate from totals.
     */
    @Test
    void shouldCalculateRepresentativeMonthFromDatabaseQueries() {
        UUID roomA = room("A1", doubleType);
        UUID roomB = room("B1", familyType);
        period(roomA, doubleType, null, LONG_AGO, null);
        Instant down = at(2026, 3, 10, 15, 0);
        Instant up = at(2026, 3, 15, 10, 0);
        period(roomB, familyType, null, LONG_AGO, down);
        period(roomB, familyType, "OUT_OF_ORDER", down, up);
        period(roomB, familyType, null, up, null);

        occupy(roomA, at(2026, 3, 5, 14, 0), at(2026, 3, 8, 11, 0));
        occupy(roomA, at(2026, 3, 30, 14, 0), at(2026, 4, 2, 11, 0));
        UUID pair = reservation();
        assign(pair, roomB, at(2026, 3, 2, 14, 0), at(2026, 3, 4, 11, 0));

        MonthlyOccupancyReport report = service.report(YearMonth.of(2026, 3));

        assertEquals(7, report.occupiedRoomNights());
        assertEquals(31 + 26, report.sellableRoomNights());
        assertEquals(new BigDecimal("12.28"), report.occupancyRate());
        assertEquals("DOUBLE", report.roomTypePerformance().get(0).roomTypeCode());
        assertEquals(5, report.roomTypePerformance().get(0).occupiedRoomNights());
        assertEquals(31, report.roomTypePerformance().get(0).sellableRoomNights());
        assertEquals("FAMILY", report.roomTypePerformance().get(1).roomTypeCode());
        assertEquals(2, report.roomTypePerformance().get(1).occupiedRoomNights());
        assertEquals(26, report.roomTypePerformance().get(1).sellableRoomNights());
        assertEquals(1, service.report(YearMonth.of(2026, 4)).occupiedRoomNights());
    }

    /** Confirms a stay ending on the 1st contributes its last night to the previous month only. */
    @Test
    void shouldRespectAssignmentOverlapBoundaries() {
        UUID room = room("A1", doubleType);
        period(room, doubleType, null, LONG_AGO, null);
        occupy(room, at(2026, 2, 28, 14, 0), at(2026, 3, 1, 11, 0));

        assertEquals(1, service.report(YearMonth.of(2026, 2)).occupiedRoomNights());
        assertEquals(0, service.report(YearMonth.of(2026, 3)).occupiedRoomNights());
    }

    /** Confirms a Room Change lineage and an assignment still open count each night once (open ends at report end). */
    @Test
    void shouldCountRoomChangeLineageOnceAndOpenAssignmentToReportEnd() {
        UUID room101 = room("101", doubleType);
        UUID room201 = room("201", doubleType);
        period(room101, doubleType, null, LONG_AGO, null);
        period(room201, doubleType, null, LONG_AGO, null);
        UUID reservation = reservation();
        UUID lineage = reservationRoom(reservation, room101);
        UUID stay = stay(reservation, at(2026, 3, 1, 14, 0), null);
        assignment(stay, room101, lineage, at(2026, 3, 1, 14, 0), at(2026, 3, 2, 16, 0));
        assignment(stay, room201, lineage, at(2026, 3, 2, 16, 0), null);

        MonthlyOccupancyReport report = service.report(YearMonth.of(2026, 3));

        assertEquals(31, report.occupiedRoomNights());
    }

    /** Confirms a month before the BOOTSTRAP boundary is rejected and the following month is reported. */
    @Test
    void shouldRejectMonthBeforeBootstrapBoundaryAndAllowTheNext() {
        UUID room = room("A1", doubleType);
        period(room, doubleType, null, at(2026, 3, 20, 10, 0), null, "BOOTSTRAP");

        ReportPeriodUnavailableException exception = assertThrows(
                ReportPeriodUnavailableException.class, () -> service.report(YearMonth.of(2026, 3)));

        assertEquals(Reason.HISTORY_UNAVAILABLE, exception.getReason());
        assertEquals(YearMonth.of(2026, 4), exception.getFirstSupportedMonth());
        assertEquals(30, service.report(YearMonth.of(2026, 4)).sellableRoomNights());
    }

    /** Confirms a fresh database without BOOTSTRAP rows rejects no past month and fabricates no boundary. */
    @Test
    void shouldNotRejectPastMonthWithoutBootstrapRows() {
        UUID room = room("A1", doubleType);
        period(room, doubleType, null, LONG_AGO, null);

        assertEquals(31, service.report(YearMonth.of(2026, 3)).sellableRoomNights());
    }

    /** Confirms an occupied night with no inventory history fails the report. */
    @Test
    void shouldFailWhenOccupiedRoomHasNoInventoryHistory() {
        UUID room = room("A1", doubleType);
        occupy(room, at(2026, 3, 5, 14, 0), at(2026, 3, 8, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service.report(YearMonth.of(2026, 3)));
    }

    /** Confirms a Stay room with no assignment at all fails the report. */
    @Test
    void shouldFailWhenStayRoomHasNoAssignment() {
        UUID room = room("A1", doubleType);
        period(room, doubleType, null, LONG_AGO, null);
        UUID reservation = reservation();
        reservationRoom(reservation, room);
        stay(reservation, at(2026, 3, 5, 14, 0), at(2026, 3, 8, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service.report(YearMonth.of(2026, 3)));
    }

    /** Confirms a future month is rejected against the real hotel clock. */
    @Test
    void shouldRejectFutureMonth() {
        ReportPeriodUnavailableException exception = assertThrows(
                ReportPeriodUnavailableException.class, () -> service.report(YearMonth.of(2999, 1)));

        assertEquals(Reason.FUTURE_MONTH, exception.getReason());
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    private UUID room(String number, UUID type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, "
                + "updated_by) VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", id, number, type, user, user);
        return id;
    }

    private void period(UUID room, UUID type, String reason, Instant from, Instant to) {
        period(room, type, reason, from, to, "RECORDED");
    }

    private void period(UUID room, UUID type, String reason, Instant from, Instant to, String origin) {
        jdbc.update("INSERT INTO room_inventory_period (id, room_id, room_type_id, unavailable_reason, origin, effective_from, "
                        + "effective_to, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), room, type, reason, origin, Timestamp.from(from),
                to == null ? null : Timestamp.from(to), user, user);
    }

    private UUID reservation() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CHECKED_OUT', now(), '2026-01-01', '2026-01-02', 'VND', 1, now(), ?, now(), ?)",
                id, "OCC-%04d".formatted(++sequence), guest, user, user);
        return id;
    }

    private UUID reservationRoom(UUID reservation, UUID room) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, '2026-01-01', '2026-01-02', 1, 1, now(), ?, now(), ?)",
                id, reservation, room, user, user);
        return id;
    }

    private UUID stay(UUID reservation, Instant checkIn, Instant checkOut) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, created_at, "
                        + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?)",
                id, reservation, checkOut == null ? "CHECKED_IN" : "CHECKED_OUT", Timestamp.from(checkIn),
                checkOut == null ? null : Timestamp.from(checkOut), user, user);
        return id;
    }

    private void assignment(UUID stay, UUID room, UUID lineage, Instant from, Instant to) {
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                        + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, lineage, Timestamp.from(from), to == null ? null : Timestamp.from(to),
                user, user);
    }

    private void occupy(UUID room, Instant from, Instant to) {
        assign(reservation(), room, from, to);
    }

    private void assign(UUID reservation, UUID room, Instant from, Instant to) {
        UUID lineage = reservationRoom(reservation, room);
        UUID stay = stay(reservation, from, to);
        assignment(stay, room, lineage, from, to);
    }
}
