package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
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
 * Verifies the Monthly Financial Report against PostgreSQL: the real queries apply the status eligibility,
 * overlap, and recognition rules of spec §61.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MonthlyFinancialReportIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MonthlyFinancialReportService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
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

    /** Clears financial data and creates the audit user and guest shared by the scenarios. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM additional_revenue");
        jdbc.update("DELETE FROM expense");
        sequence = 0;
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "fin." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
    }

    /** Confirms only CHECKED_IN and CHECKED_OUT reservations count, allocated by night across months. */
    @Test
    void shouldCountOnlyEligibleStatusesAndAllocateAcrossMonths() {
        // Dates are safely in the past so a CHECKED_IN row is not capped by the real hotel clock (spec 61.6):
        // recognizedEnd only limits a CHECKED_IN interval to nights that have already started.
        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED", "NO_SHOW")) {
            reservationRoom(status, "VND", "2020-09-30", "2020-10-03", "1000000");
        }
        reservationRoom("CHECKED_IN", "VND", "2020-09-30", "2020-10-03", "1000000");
        reservationRoom("CHECKED_OUT", "VND", "2020-09-30", "2020-10-03", "1000000");

        assertMoney("2000000", service.report(YearMonth.of(2020, 9)).roomRevenue());
        assertMoney("4000000", service.report(YearMonth.of(2020, 10)).roomRevenue());
        assertMoney("0", service.report(YearMonth.of(2020, 8)).roomRevenue());
        assertMoney("0", service.report(YearMonth.of(2020, 11)).roomRevenue());
    }

    /** Confirms the overlap boundaries: a stay ending on the 1st and one starting on the last day. */
    @Test
    void shouldRespectMonthBoundaries() {
        reservationRoom("CHECKED_OUT", "VND", "2026-08-30", "2026-09-01", "500000");
        reservationRoom("CHECKED_OUT", "VND", "2026-09-30", "2026-10-01", "500000");

        assertMoney("500000", service.report(YearMonth.of(2026, 9)).roomRevenue());
        assertMoney("1000000", service.report(YearMonth.of(2026, 8)).roomRevenue());
        assertMoney("0", service.report(YearMonth.of(2026, 10)).roomRevenue());
    }

    /** Confirms non-VND rows are excluded with warning metadata, and VND rows are unaffected. */
    @Test
    void shouldExcludeNonVndAndWarn() {
        reservationRoom("CHECKED_OUT", "VND", "2026-09-10", "2026-09-12", "1000000");
        UUID usd = reservation("CHECKED_OUT", "USD");
        room(usd, "2026-09-10", "2026-09-12", "100");
        room(usd, "2026-09-10", "2026-09-12", "100");

        MonthlyFinancialReport report = service.report(YearMonth.of(2026, 9));

        assertMoney("2000000", report.roomRevenue());
        assertEquals(1, report.nonVndWarning().reservationCount());
        assertEquals(2, report.nonVndWarning().reservationRoomCount());
        assertEquals(List.of("USD"), report.nonVndWarning().currencies());
        assertNull(service.report(YearMonth.of(2026, 10)).nonVndWarning());
    }

    /** Confirms Additional Revenue counts RECORDED only and Expense counts POSTED only, by their dates. */
    @Test
    void shouldRecognizeRecordedRevenueAndPostedExpenseByDate() {
        additionalRevenue("RECORDED", "2026-09-01", "300000");
        additionalRevenue("RECORDED", "2026-09-30", "200000");
        additionalRevenue("RECORDED", "2026-10-01", "999");
        additionalRevenue("VOIDED", "2026-09-15", "777777");
        expense("POSTED", "2026-09-05", "100000");
        expense("POSTED", "2026-08-31", "888");
        for (String status : List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED")) {
            expense(status, "2026-09-10", "555555");
        }

        MonthlyFinancialReport report = service.report(YearMonth.of(2026, 9));

        assertMoney("500000", report.additionalRevenue());
        assertMoney("100000", report.expense());
        assertMoney("400000", report.netProfit());
        assertEquals(new BigDecimal("80.00"), report.profitMargin());
    }

    /** Confirms an empty month is all zero with no margin and no warning. */
    @Test
    void shouldReturnZeroForEmptyMonth() {
        MonthlyFinancialReport report = service.report(YearMonth.of(2026, 9));

        assertMoney("0", report.totalRevenue());
        assertMoney("0", report.expense());
        assertNull(report.profitMargin());
        assertNull(report.nonVndWarning());
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private UUID reservation(String status, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), '2026-01-01', '2026-01-02', ?, 1, now(), ?, now(), ?)",
                id, "FIN-%04d".formatted(++sequence), guest, status, currency, user, user);
        return id;
    }

    private void room(UUID reservation, String checkIn, String checkOut, String rate) {
        UUID room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", room, "F" + room.toString().substring(0, 8),
                SINGLE_ROOM_TYPE_ID, user, user);
        LocalDate in = LocalDate.parse(checkIn);
        LocalDate out = LocalDate.parse(checkOut);
        BigDecimal nightly = new BigDecimal(rate);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), reservation, room, in, out, nightly,
                nightly.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, out))), user, user);
    }

    private void reservationRoom(String status, String currency, String checkIn, String checkOut, String rate) {
        room(reservation(status, currency), checkIn, checkOut, rate);
    }

    private void additionalRevenue(String status, String date, String amount) {
        boolean voided = status.equals("VOIDED");
        jdbc.update("INSERT INTO additional_revenue (id, category_id, amount, currency, revenue_date, payment_method, status, "
                        + "void_reason, voided_at, voided_by, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, '00000000-0000-0000-0000-000000000402', ?, 'VND', ?, 'CASH', ?, ?, "
                        + (voided ? "now(), ?" : "NULL, NULL") + ", now(), ?, now(), ?)",
                voided
                        ? new Object[] {UUID.randomUUID(), new BigDecimal(amount), LocalDate.parse(date), status, "duplicate", user, user, user}
                        : new Object[] {UUID.randomUUID(), new BigDecimal(amount), LocalDate.parse(date), status, null, user, user});
    }

    private void expense(String status, String date, String amount) {
        UUID category = jdbc.queryForObject("SELECT id FROM expense_category LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO expense (id, category_id, amount, currency, expense_date, payment_method, status, "
                        + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 'VND', ?, 'CASH', ?, now(), ?, now(), ?)",
                UUID.randomUUID(), category, new BigDecimal(amount), LocalDate.parse(date), status, user, user);
    }
}
