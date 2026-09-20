package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.entity.booking.BookingSource;
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
 * Verifies the new Monthly Hotel Performance queries against PostgreSQL: Reservation Source counts (all
 * statuses, by check-in date) and Additional Revenue per category (RECORDED only, ordered by amount then code).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MonthlyHotelPerformanceReportIntegrationTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MonthlyHotelPerformanceReportService service;

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

    /** Clears the affected data and creates the shared audit user and guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM room_inventory_period");
        jdbc.update("DELETE FROM additional_revenue");
        jdbc.update("DELETE FROM expense");
        sequence = 0;
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "perf." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
    }

    /** Confirms sources count every status by check-in date, with exclusive month end and zero-filled sources. */
    @Test
    void shouldCountReservationsBySourceForAllStatusesByCheckInDate() {
        reservation("DIRECT", "DRAFT", "2026-03-01");
        reservation("DIRECT", "CANCELLED", "2026-03-31");
        reservation("AGODA", "NO_SHOW", "2026-03-15");
        reservation("AGODA", "CHECKED_OUT", "2026-03-16");
        reservation("AGODA", "CONFIRMED", "2026-03-17");
        reservation("DIRECT", "CONFIRMED", "2026-04-01");
        reservation("BOOKING_COM", "CONFIRMED", "2026-02-28");

        MonthlyHotelPerformanceReport report = service.build(MARCH);

        assertEquals(5, report.reservationCount());
        List<ReservationSourceShare> sources = report.reservationSources();
        assertEquals(BookingSource.DIRECT, sources.get(0).source());
        assertEquals(2, sources.get(0).count());
        assertEquals(new BigDecimal("40.00"), sources.get(0).percentage());
        assertEquals(3, sources.get(1).count());
        assertEquals(new BigDecimal("60.00"), sources.get(1).percentage());
        assertEquals(0, sources.get(2).count());
        assertEquals(0, sources.get(3).count());
    }

    /** Confirms categories aggregate RECORDED only, exclude VOIDED and other months, and order by amount then code. */
    @Test
    void shouldAggregateRecordedAdditionalRevenueByCategoryWithStableOrder() {
        List<UUID> categories = jdbc.queryForList("SELECT id FROM additional_revenue_category ORDER BY code", UUID.class);
        UUID first = categories.get(0);
        UUID second = categories.get(1);
        UUID third = category("ZZZ_LAST");
        UUID fourth = category("AAA_FIRST");
        revenue(first, "RECORDED", "2026-03-01", "200");
        revenue(first, "RECORDED", "2026-03-31", "100");
        revenue(second, "RECORDED", "2026-03-10", "300");
        revenue(third, "RECORDED", "2026-03-10", "50");
        revenue(fourth, "RECORDED", "2026-03-11", "50");
        revenue(second, "VOIDED", "2026-03-12", "9999");
        revenue(first, "RECORDED", "2026-04-01", "8888");
        revenue(first, "RECORDED", "2026-02-28", "7777");

        MonthlyHotelPerformanceReport report = service.build(MARCH);

        var shares = report.additionalRevenueByCategory();
        assertEquals(4, shares.size());
        assertEquals(0, new BigDecimal("300").compareTo(shares.get(0).totalAmount()));
        assertEquals(0, new BigDecimal("300").compareTo(shares.get(1).totalAmount()));
        assertEquals("AAA_FIRST", shares.get(2).categoryCode(), "equal totals order by code ascending");
        assertEquals("ZZZ_LAST", shares.get(3).categoryCode());
        assertEquals(new BigDecimal("42.86"), shares.get(0).percentage().add(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP));
        assertEquals(0, new BigDecimal("700").compareTo(report.financial().additionalRevenue()));
    }

    /** Confirms a supported month with no data builds a full dataset of zeros without fabricated rows. */
    @Test
    void shouldBuildZeroDataMonthEndToEnd() {
        MonthlyHotelPerformanceReport report = service.build(MARCH);

        assertEquals(6, report.revenueTrend().size());
        assertEquals(YearMonth.of(2025, 10), report.revenueTrend().get(0).month());
        assertEquals(MARCH, report.revenueTrend().get(5).month());
        assertEquals(0, report.reservationCount());
        assertEquals(0, report.additionalRevenueByCategory().size());
        assertEquals(4, report.reservationSources().size());
        assertEquals(0, report.financial().totalRevenue().signum());
    }

    private UUID category(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO additional_revenue_category (id, code, name, description, active, created_at, created_by, "
                + "updated_at, updated_by) VALUES (?, ?, ?, NULL, TRUE, now(), ?, now(), ?)", id, code, code, user, user);
        return id;
    }

    private void revenue(UUID category, String status, String date, String amount) {
        boolean voided = status.equals("VOIDED");
        jdbc.update("INSERT INTO additional_revenue (id, category_id, amount, currency, revenue_date, payment_method, status, "
                        + "void_reason, voided_at, voided_by, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'VND', ?, 'CASH', ?, ?, " + (voided ? "now(), ?" : "NULL, NULL") + ", now(), ?, now(), ?)",
                voided
                        ? new Object[] {UUID.randomUUID(), category, new BigDecimal(amount), LocalDate.parse(date), status, "duplicate", user, user, user}
                        : new Object[] {UUID.randomUUID(), category, new BigDecimal(amount), LocalDate.parse(date), status, null, user, user});
    }

    private void reservation(String source, String status, String checkIn) {
        LocalDate in = LocalDate.parse(checkIn);
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, external_booking_id, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                UUID.randomUUID(), "PERF-%04d".formatted(++sequence), guest, source,
                source.equals("DIRECT") ? null : "EXT-" + sequence, status, in, in.plusDays(1), user, user);
    }
}
