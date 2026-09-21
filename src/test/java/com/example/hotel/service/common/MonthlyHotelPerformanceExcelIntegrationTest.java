package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.repository.booking.PaymentExportRow;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationExportRow;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.ReservationRoomTypeRow;
import com.example.hotel.repository.common.ExpenseExportRow;
import com.example.hotel.repository.common.ExpenseRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Verifies the three Excel detail queries against PostgreSQL: Reservation population and batched room types,
 * Payment statuses and hotel-zone Instant boundaries, and POSTED Expense with category and creator resolved.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MonthlyHotelPerformanceExcelIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 1);
    private static final LocalDate END = LocalDate.of(2026, 4, 1);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private MonthlyHotelPerformanceExcelService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private String username;
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

    /** Clears affected data and creates the audit user (with a known username) and a named guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM room_inventory_period");
        jdbc.update("DELETE FROM room");
        jdbc.update("DELETE FROM expense");
        sequence = 0;
        user = UUID.randomUUID();
        username = "exporter-" + user;
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, username);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Maria', 'Santos', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
    }

    /** Confirms population by check-in date (all statuses, exclusive end), ordering, and distinct batched room types. */
    @Test
    void shouldReturnReservationsCheckingInInTheMonthWithDistinctRoomTypes() {
        reservation("DRAFT", "2026-03-31");
        UUID multi = reservation("CANCELLED", "2026-03-01");
        reservation("CHECKED_OUT", "2026-03-01");
        reservation("CONFIRMED", "2026-04-01");
        reservation("CONFIRMED", "2026-02-28");
        room(multi, "DOUBLE");
        room(multi, "TWIN");
        room(multi, "DOUBLE");

        List<ReservationExportRow> rows = reservations.findExportRowsByCheckInWithin(START, END);

        assertEquals(3, rows.size());
        assertEquals(List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
                rows.stream().map(ReservationExportRow::checkInDate).toList());
        assertTrue(rows.get(0).reservationNumber().compareTo(rows.get(1).reservationNumber()) < 0);
        assertEquals("Maria", rows.get(0).guestFirstName());
        assertEquals("Santos", rows.get(0).guestLastName());
        List<ReservationRoomTypeRow> types = reservations.findBookedRoomTypesByCheckInWithin(START, END);
        assertEquals(2, types.size(), "three booked rooms, two distinct types, no duplicates");
        assertEquals(List.of("DOUBLE", "TWIN"), types.stream().map(ReservationRoomTypeRow::roomTypeCode).toList());
        assertEquals(multi, types.get(0).reservationId());
        MonthlyHotelPerformanceExcelData data = service.build(YearMonth.of(2026, 3));
        assertEquals(data.report().reservationCount(), data.reservations().size());
        assertEquals(3, data.reservations().size());
    }

    /** Confirms PAID and REFUNDED only, hotel-zone month bounds on paidAt, ordering, and the Stay-Reservation-Guest mapping. */
    @Test
    void shouldReturnPaidAndRefundedPaymentsPaidInTheMonth() {
        UUID reservation = reservation("CHECKED_OUT", "2026-03-05");
        UUID stay = stay(reservation);
        payment(stay, "PAID", "2026-02-28T17:00:00Z", "100", "VND");
        payment(stay, "REFUNDED", "2026-03-10T03:00:00Z", "200.50", "USD");
        payment(stay, "PAID", "2026-03-31T16:59:59Z", "300", "VND");
        payment(stay, "PAID", "2026-02-28T16:59:59Z", "999", "VND");
        payment(stay, "PAID", "2026-03-31T17:00:00Z", "888", "VND");
        payment(stay, "PENDING", null, "777", "VND");
        payment(stay, "FAILED", null, "666", "VND");

        List<PaymentExportRow> rows = payments.findExportRowsPaidWithin(
                List.of(PaymentStatus.PAID, PaymentStatus.REFUNDED),
                Instant.parse("2026-02-28T17:00:00Z"), Instant.parse("2026-03-31T17:00:00Z"));

        assertEquals(3, rows.size());
        assertEquals(List.of(100d, 200.5d, 300d), rows.stream().map(row -> row.amount().doubleValue()).toList());
        assertEquals(PaymentStatus.REFUNDED, rows.get(1).status());
        assertEquals("USD", rows.get(1).currency().name());
        assertEquals("Maria", rows.get(0).guestFirstName());
        assertEquals("Santos", rows.get(0).guestLastName());
        assertTrue(rows.get(0).reservationNumber().startsWith("EXP-"));
        assertEquals("Front Desk", rows.get(0).reference());
        assertEquals(3, service.build(YearMonth.of(2026, 3)).payments().size());
    }

    /** Confirms only POSTED expenses of the month, exclusive end, ordered, with category name and creator username. */
    @Test
    void shouldReturnPostedExpensesWithCategoryAndCreator() {
        UUID category = jdbc.queryForObject("SELECT id FROM expense_category ORDER BY code LIMIT 1", UUID.class);
        String categoryName = jdbc.queryForObject("SELECT name FROM expense_category WHERE id = ?", String.class, category);
        expense(category, "POSTED", "2026-03-31", "300");
        expense(category, "POSTED", "2026-03-01", "100");
        expense(category, "POSTED", "2026-04-01", "999");
        expense(category, "POSTED", "2026-02-28", "888");
        expense(category, "DRAFT", "2026-03-10", "777");
        expense(category, "APPROVED", "2026-03-11", "666");

        List<ExpenseExportRow> rows = expenses.findExportRowsByStatusWithin(ExpenseStatus.POSTED, START, END);

        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 3, 1), rows.get(0).expenseDate());
        assertEquals(LocalDate.of(2026, 3, 31), rows.get(1).expenseDate());
        assertEquals(categoryName, rows.get(0).categoryName());
        assertEquals(username, rows.get(0).createdByUsername());
        assertEquals("Expense 100", rows.get(0).description());
        assertEquals(2, service.build(YearMonth.of(2026, 3)).expenses().size());
    }

    private UUID reservation(String status, String checkIn) {
        UUID id = UUID.randomUUID();
        LocalDate in = LocalDate.parse(checkIn);
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, "
                        + "check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, "EXP-%04d".formatted(++sequence), guest, status, in, in.plusDays(1), user, user);
        return id;
    }

    private void room(UUID reservation, String roomTypeCode) {
        UUID type = jdbc.queryForObject("SELECT id FROM room_type WHERE code = ?", UUID.class, roomTypeCode);
        UUID room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", room, "X" + room.toString().substring(0, 8), type, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, '2026-03-01', '2026-03-02', 1, 1, now(), ?, now(), ?)",
                UUID.randomUUID(), reservation, room, user, user);
    }

    private UUID stay(UUID reservation) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_OUT', now(), now(), ?, now(), ?)", id, reservation, user, user);
        return id;
    }

    private void payment(UUID stay, String status, String paidAt, String amount, String currency) {
        jdbc.update("INSERT INTO payment (id, stay_id, amount, currency, applied_amount, method, status, paid_at, reference, "
                        + "refund_reason, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'CASH', ?, ?, 'Front Desk', ?, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, new BigDecimal(amount), currency, new BigDecimal(amount), status,
                paidAt == null ? null : Timestamp.from(Instant.parse(paidAt)),
                status.equals("REFUNDED") ? "guest request" : null, user, user);
    }

    private void expense(UUID category, String status, String date, String amount) {
        jdbc.update("INSERT INTO expense (id, category_id, amount, currency, expense_date, payment_method, description, status, "
                        + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 'VND', ?, 'CASH', ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), category, new BigDecimal(amount), LocalDate.parse(date), "Expense " + amount, status, user, user);
    }
}
