package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import org.springframework.web.server.ResponseStatusException;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.RoomChangeService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.common.MonthlyFinancialReportService;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Overdue Departure V1 against PostgreSQL: an overdue stay cannot check out, the Stay Extension (to today at the
 * earliest) bills the overdue night(s) and lifts the block, prepayment does not bypass it, inventory and Room Change
 * semantics are preserved, a next arrival on the room can be recovered by reassignment, and the extension/check-out race
 * always keeps the Stay lock order.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OverdueDepartureIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StayExtensionService extensionService;

    @Autowired
    private RoomChangeService roomChangeService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private PrepaymentService prepayments;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StayBalanceService balances;

    @Autowired
    private RoomAvailabilityService availability;

    @Autowired
    private FrontDeskQueryService frontDesk;

    @Autowired
    private ReservationRoomReassignmentService reassignment;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private LocalDate today;

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

    /** Clears bookings and creates the acting user and guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHANGE_ROOM', 'CONFIRM', 'EXTEND_STAY', 'RECORD_PREPAYMENT', 'APPLY_PREPAYMENT', 'REFUND_PAYMENT', 'CHECK_IN', 'CHECK_OUT', 'REASSIGN_ROOM')");
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NOT NULL");
        jdbc.update("DELETE FROM stay_extension_room");
        jdbc.update("DELETE FROM stay_extension");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "od." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    private static PaymentCreateRequest cash(String amount) {
        return new PaymentCreateRequest(new BigDecimal(amount), PaymentCurrency.VND, null, PaymentMethod.CASH, null);
    }

    /**
     * Seeds an overdue CHECKED_IN stay of 2 nights x 1,000,000 that was planned to end yesterday, with its original
     * ROOM charge (2,000,000) linked to the booked room. Returns the Stay id.
     */
    private UUID overdueStay(UUID room) {
        UUID stay = seededCheckedIn(room, today.minusDays(3), today.minusDays(1));
        UUID line = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservationOf(stay));
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, charged_at, created_at, created_by, "
                + "updated_at, updated_by, source_reservation_room_id) VALUES (?, ?, 'ROOM', 'Room', 2, 1000000, 2000000, now(), now(), ?, now(), ?, ?)",
                UUID.randomUUID(), stay, user, user, line);
        return stay;
    }

    private void assertCheckOutRejectedWithoutMutation(UUID reservation, UUID stay, UUID room) {
        assertThrows(ResponseStatusException.class, () -> reservationService.checkOut(reservation));
        assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT status FROM stay WHERE id = ?", String.class, stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay WHERE id = ? AND actual_check_out_at IS NULL", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? AND assigned_to IS NULL", stay));
        assertEquals("OCCUPIED", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room));
    }

    /** Confirms the full flow: blocked, extend exactly to today (one night), settle, then check out. */
    @Test
    void shouldResolveAnOverdueStayThroughExtensionThenCheckOut() {
        UUID room = room("OD-A", "OCCUPIED");
        UUID stay = overdueStay(room);
        UUID reservation = reservationOf(stay);
        assertTrue(frontDesk.departures(true).stream().anyMatch(r -> r.reservationId().equals(reservation) && r.overdueDays() == 1));
        assertEquals(0, balances.calculate(stay).outstanding().compareTo(new BigDecimal("2000000")));
        paymentService.recordPaid(stay, cash("2000000"));
        assertEquals(0, balances.calculate(stay).outstanding().signum());

        assertCheckOutRejectedWithoutMutation(reservation, stay, room);
        assertThrows(StayExtensionException.class, () -> extensionService.extend(
                reservation, new StayExtensionRequest(today.minusDays(1), today.minusDays(1))));

        extensionService.extend(reservation, new StayExtensionRequest(today.minusDays(1), today));

        assertEquals(today, jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE from_date = ? AND to_date = ? AND amount = 1000000", today.minusDays(1), today));
        assertEquals(0, new BigDecimal("3000000").compareTo(extensionService.summary(reservation).currentAccommodationTotal()));
        assertEquals(0, new BigDecimal("1000000").compareTo(balances.calculate(stay).outstanding()));
        assertFalse(frontDesk.departures(true).stream().anyMatch(r -> r.reservationId().equals(reservation) && r.overdue()));
        assertThrows(ResponseStatusException.class, () -> reservationService.checkOut(reservation), "still owes the extension night");

        paymentService.recordPaid(stay, cash("1000000"));
        reservationService.checkOut(reservation);

        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals(today, jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation),
                "check-out never rewrites the planned date");
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room));
    }

    /** Confirms an overdue stay with a full prepayment (zero outstanding) is still blocked, and extension re-opens the balance. */
    @Test
    void shouldStillBlockAnOverdueStayWithAFullPrepayment() {
        UUID room = room("OD-P", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(2), new Line(room, "2000000"));
        prepayments.record(reservation, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.BANK_TRANSFER, "FULL"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        // Simulate the stay running past its planned departure (planned check-out is now yesterday).
        jdbc.update("UPDATE reservation SET check_in_date = ?, check_out_date = ? WHERE id = ?", today.minusDays(3), today.minusDays(1), reservation);
        assertEquals(0, balances.calculate(stay).outstanding().signum());

        assertCheckOutRejectedWithoutMutation(reservation, stay, room);
        extensionService.extend(reservation, new StayExtensionRequest(today.minusDays(1), today));

        assertEquals(0, new BigDecimal("2000000").compareTo(balances.calculate(stay).outstanding()), "one night at the original 2,000,000 rate");
        paymentService.recordPaid(stay, cash("2000000"));
        assertEquals(0, balances.calculate(stay).outstanding().signum());
        reservationService.checkOut(reservation);
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
    }

    /** Confirms inventory protection is unchanged: the current night while overdue, the extended interval afterwards. */
    @Test
    void shouldKeepTheInventoryProtectionUnchanged() {
        UUID room = room("OD-I", "OCCUPIED");
        UUID stay = overdueStay(room);

        assertTrue(availability.hasInventoryConflict(room, today, today.plusDays(1)), "the current night is protected");
        assertFalse(availability.hasInventoryConflict(room, today.plusDays(1), today.plusDays(2)), "the future is not");

        extensionService.extend(reservationOf(stay), new StayExtensionRequest(today.minusDays(1), today.plusDays(2)));

        assertTrue(availability.hasInventoryConflict(room, today, today.plusDays(2)));
        assertFalse(availability.hasInventoryConflict(room, today.plusDays(2), today.plusDays(3)));
    }

    /** Confirms Room Change semantics are preserved: rejected while today is on or after the planned check-out. */
    @Test
    void shouldKeepTheRoomChangeRuleUnchanged() {
        UUID a = room("OD-RA", "OCCUPIED");
        UUID b = room("OD-RB", "AVAILABLE");
        UUID stay = overdueStay(a);
        UUID reservation = reservationOf(stay);
        var change = new com.example.hotel.dto.booking.request.RoomChangeRequest(
                b, com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null);

        assertThrows(ResponseStatusException.class, () -> roomChangeService.changeRoom(reservation, a, change));
        extensionService.extend(reservation, new StayExtensionRequest(today.minusDays(1), today));
        assertThrows(ResponseStatusException.class, () -> roomChangeService.changeRoom(reservation, a, change), "planned check-out == today");
        extensionService.extend(reservation, new StayExtensionRequest(today, today.plusDays(1)));
        roomChangeService.changeRoom(reservation, a, change);
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? AND room_id = ? AND assigned_to IS NULL", stay, b));
    }

    /** Confirms a next arrival on an overdue room cannot check in, is flagged on Front Desk, and reassignment recovers it. */
    @Test
    void shouldRecoverANextArrivalOnAnOverdueRoomThroughReassignment() {
        UUID a = room("OD-CA", "OCCUPIED");
        UUID c = room("OD-CC", "AVAILABLE");
        overdueStay(a);
        UUID next = confirmed(today, today.plusDays(1), new Line(a, "1000000"));

        assertThrows(ResponseStatusException.class, () -> reservationService.checkIn(next));
        assertTrue(frontDesk.arrivals().stream().anyMatch(r -> r.reservationId().equals(next) && r.needsAttention()));

        reassignment.reassign(next, a, c);
        reservationService.checkIn(next);

        assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, next));
    }

    /** Confirms an extension racing check-out on an overdue stay never lets the check-out succeed. */
    @Test
    void shouldNeverCheckOutAnOverdueStayWhileAnExtensionRaces() throws Exception {
        for (int i = 0; i < 10; i++) {
            setUp();
            UUID room = room("OD-R", "OCCUPIED");
            UUID stay = overdueStay(room);
            UUID reservation = reservationOf(stay);
            paymentService.recordPaid(stay, cash("2000000"));

            List<Object> results = race(
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.minusDays(1), today)),
                    () -> reservationService.checkOut(reservation));

            assertTrue(results.get(0) instanceof String, "the extension always succeeds: " + results);
            assertTrue(results.get(1) instanceof ResponseStatusException, "check-out is rejected in either order: " + results);
            assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
            assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
        }
    }

    private record Line(UUID room, String rate) {}

    private List<Object> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (Runnable task : List.of(first, second)) {
            Callable<Object> call = () -> {
                authenticate();
                barrier.await();
                try {
                    task.run();
                    return "ok";
                } catch (RuntimeException exception) {
                    return exception;
                }
            };
            futures.add(pool.submit(call));
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private void markCleaned(UUID room) {
        jdbc.update("UPDATE room SET status = 'AVAILABLE' WHERE id = ?", room);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private LocalDate checkOutOf(UUID reservation) {
        return jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
    }

    private UUID stayOf(UUID reservation) {
        return jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
    }

    private UUID reservationOf(UUID stay) {
        return jdbc.queryForObject("SELECT reservation_id FROM stay WHERE id = ?", UUID.class, stay);
    }

    private UUID room(String number, String status) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET status = EXCLUDED.status",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, status, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID confirmed(LocalDate in, LocalDate out, Line... lines) {
        return reservation("CONFIRMED", in, out, lines);
    }

    private UUID draft(LocalDate in, LocalDate out, Line... lines) {
        return reservation("DRAFT", in, out, lines);
    }

    private UUID reservation(String status, LocalDate in, LocalDate out, Line... lines) {
        UUID id = UUID.randomUUID();
        BigDecimal total = BigDecimal.ZERO;
        long nights = java.time.temporal.ChronoUnit.DAYS.between(in, out);
        for (Line line : lines) {
            total = total.add(new BigDecimal(line.rate()).multiply(BigDecimal.valueOf(nights)));
        }
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "OD" + id.toString().substring(0, 12), guest, status, in, out, total, user, user);
        for (Line line : lines) {
            jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                    + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                    UUID.randomUUID(), id, line.room(), in, out, new BigDecimal(line.rate()),
                    new BigDecimal(line.rate()).multiply(BigDecimal.valueOf(nights)), user, user);
        }
        return id;
    }

    /** Seeds a CHECKED_IN reservation, Stay and open assignment (no original charge); returns the Stay id. */
    private UUID seededCheckedIn(UUID room, LocalDate in, LocalDate out) {
        UUID reservation = reservation("CHECKED_IN", in, out, new Line(room, "1000000"));
        UUID stay = UUID.randomUUID();
        // Midnight of the check-in day, never a fixed wall-clock hour: every caller passes in <= today, so this is
        // always <= any later real Instant.now(clock) a production close (Room Change, checkout) computes the same
        // day - unlike a fixed hour such as 14:00, which is in the future whenever the suite runs before it.
        Instant from = in.atStartOfDay(ZONE).toInstant();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', ?, now(), ?, now(), ?)", stay, reservation, Timestamp.from(from), user, user);
        UUID lineId = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, lineId, Timestamp.from(from), user, user);
        jdbc.update("UPDATE room SET status = 'OCCUPIED' WHERE id = ?", room);
        return stay;
    }

    private UUID insertCharge(UUID stay, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, charged_at, created_at, created_by, "
                + "updated_at, updated_by) VALUES (?, ?, 'ROOM', 'x', 2, 1000000, ?, now(), now(), ?, now(), ?)",
                id, stay, new BigDecimal(amount), user, user);
        return id;
    }

    private UUID insertExtension(UUID stay, UUID room, LocalDate from, LocalDate to, int sequence) {
        UUID ext = insertRawExtension(stay, sequence, from, to);
        UUID lineage = jdbc.queryForObject("SELECT original_reservation_room_id FROM stay_room_assignment WHERE stay_id = ?", UUID.class, stay);
        long nights = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        BigDecimal amount = new BigDecimal("1000000").multiply(BigDecimal.valueOf(nights));
        insertRawLine(ext, lineage, room, from, to, "1000000", amount.toPlainString(), insertCharge(stay, amount.toPlainString()));
        return ext;
    }

    private UUID insertRawExtension(UUID stay, int sequence, LocalDate previous, LocalDate next) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stay_extension (id, stay_id, sequence_no, previous_check_out_date, new_check_out_date, created_at, "
                + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?)",
                id, stay, sequence, previous, next, user, user);
        return id;
    }

    private void insertRawLine(UUID ext, UUID lineage, UUID room, LocalDate from, LocalDate to, String rate, String amount, UUID charge) {
        jdbc.update("INSERT INTO stay_extension_room (id, stay_extension_id, original_reservation_room_id, room_id, from_date, to_date, "
                + "nightly_rate, amount, charge_id, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), ext, lineage, room, from, to, new BigDecimal(rate), new BigDecimal(amount), charge, user, user);
    }
}
