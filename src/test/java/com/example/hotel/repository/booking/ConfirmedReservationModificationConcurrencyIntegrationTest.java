package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.exception.ConfirmedReservationModificationException;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies PostgreSQL lock serialization for confirmed-reservation date changes racing with check-in, pre-check-in
 * Room reassignment, prepayment recording and another date change.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConfirmedReservationModificationConcurrencyIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String OK = "ok";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservations;

    @Autowired
    private ReservationRoomReassignmentService reassignments;

    @Autowired
    private PrepaymentService prepayments;

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

    /** Clears booking data and creates the authenticated test actor and primary Guest. */
    @BeforeEach
    void setUp() {
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
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "date-race." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /** Confirms date change and check-in serialize so exactly one lifecycle outcome wins without a partial update. */
    @Test
    void shouldSerializeDateChangeWithCheckIn() throws Exception {
        UUID room = room("DCI");
        UUID reservation = reservation(today, today.plusDays(2), room, "1000000");
        LocalDate movedIn = today.plusDays(3);
        LocalDate movedOut = today.plusDays(6);

        List<Object> results = race(
                () -> reservations.changeConfirmedDates(
                        reservation, new ReservationDateChangeRequest(movedIn, movedOut)),
                () -> reservations.checkIn(reservation));

        assertEquals(1, successes(results), results.toString());
        String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
        if ("CONFIRMED".equals(status)) {
            assertSnapshot(reservation, movedIn, movedOut, "3000000");
            assertEquals(0, count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservation));
        } else {
            assertEquals("CHECKED_IN", status);
            assertSnapshot(reservation, today, today.plusDays(2), "2000000");
            assertEquals(1, count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservation));
        }
    }

    /** Confirms a date change racing Room reassignment leaves one complete Room line with synchronized dates. */
    @Test
    void shouldSerializeDateChangeWithRoomReassignment() throws Exception {
        UUID oldRoom = room("DCR-OLD");
        UUID targetRoom = room("DCR-NEW");
        LocalDate originalIn = today.plusDays(1);
        LocalDate originalOut = today.plusDays(3);
        LocalDate movedIn = today.plusDays(2);
        LocalDate movedOut = today.plusDays(5);
        UUID reservation = reservation(originalIn, originalOut, oldRoom, "1000000");

        List<Object> results = race(
                () -> reservations.changeConfirmedDates(
                        reservation, new ReservationDateChangeRequest(movedIn, movedOut)),
                () -> reassignments.reassign(reservation, oldRoom, targetRoom));

        assertTrue(successes(results) >= 1, results.toString());
        assertTrue(results.stream().allMatch(result -> OK.equals(result)
                || result instanceof ConfirmedReservationModificationException
                || result instanceof RoomReassignmentException), results.toString());
        assertEquals(1, count("SELECT COUNT(*) FROM reservation_room WHERE reservation_id = ?", reservation));
        UUID finalRoom = jdbc.queryForObject(
                "SELECT room_id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        assertTrue(finalRoom.equals(oldRoom) || finalRoom.equals(targetRoom));
        LocalDate finalIn = jdbc.queryForObject(
                "SELECT check_in_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
        LocalDate finalOut = jdbc.queryForObject(
                "SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
        String expectedTotal = finalIn.equals(movedIn) && finalOut.equals(movedOut) ? "3000000" : "2000000";
        assertSnapshot(reservation, finalIn, finalOut, expectedTotal);
    }

    /** Confirms date shortening and prepayment recording cannot commit an active amount above the final total. */
    @Test
    void shouldSerializeDateChangeWithPrepaymentRecording() throws Exception {
        UUID room = room("DCP");
        UUID reservation = reservation(today, today.plusDays(4), room, "1000000");

        List<Object> results = race(
                () -> reservations.changeConfirmedDates(
                        reservation, new ReservationDateChangeRequest(today, today.plusDays(2))),
                () -> prepayments.record(reservation, new PaymentCreateRequest(
                        new BigDecimal("3000000"), PaymentCurrency.VND, null,
                        PaymentMethod.BANK_TRANSFER, "DATE-RACE")));

        assertEquals(1, successes(results), results.toString());
        BigDecimal total = jdbc.queryForObject(
                "SELECT total_amount FROM reservation WHERE id = ?", BigDecimal.class, reservation);
        BigDecimal active = jdbc.queryForObject(
                "SELECT COALESCE(SUM(applied_amount), 0) FROM payment "
                        + "WHERE reservation_id = ? AND stay_id IS NULL AND status = 'PAID'",
                BigDecimal.class, reservation);
        assertTrue(active.compareTo(total) <= 0, "active=" + active + ", total=" + total);
        if (active.signum() == 0) {
            assertSnapshot(reservation, today, today.plusDays(2), "2000000");
        } else {
            assertEquals(0, new BigDecimal("3000000").compareTo(active));
            assertSnapshot(reservation, today, today.plusDays(4), "4000000");
        }
    }

    /** Confirms concurrent date changes serialize as complete updates, with no mixed Reservation/line snapshot. */
    @Test
    void shouldSerializeConcurrentDateChanges() throws Exception {
        UUID room = room("DCD");
        UUID reservation = reservation(today.plusDays(1), today.plusDays(3), room, "1000000");
        ReservationDateChangeRequest first = new ReservationDateChangeRequest(today.plusDays(2), today.plusDays(5));
        ReservationDateChangeRequest second = new ReservationDateChangeRequest(today.plusDays(4), today.plusDays(8));

        List<Object> results = race(
                () -> reservations.changeConfirmedDates(reservation, first),
                () -> reservations.changeConfirmedDates(reservation, second));

        assertEquals(2, successes(results), results.toString());
        LocalDate finalIn = jdbc.queryForObject(
                "SELECT check_in_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
        LocalDate finalOut = jdbc.queryForObject(
                "SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
        boolean firstWonLast = finalIn.equals(first.newCheckInDate()) && finalOut.equals(first.newCheckOutDate());
        boolean secondWonLast = finalIn.equals(second.newCheckInDate()) && finalOut.equals(second.newCheckOutDate());
        assertTrue(firstWonLast || secondWonLast, finalIn + " -> " + finalOut);
        String expectedTotal = firstWonLast ? "3000000" : "4000000";
        assertSnapshot(reservation, finalIn, finalOut, expectedTotal);
        assertEquals(2, count(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'CHANGE_RESERVATION_DATES' AND entity_id = ?",
                reservation));
    }

    /** Runs two authenticated service calls from separate transactions at the same starting barrier. */
    private List<Object> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Runnable task : List.of(first, second)) {
                Callable<Object> call = () -> {
                    authenticate();
                    barrier.await();
                    try {
                        task.run();
                        return OK;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                };
                futures.add(pool.submit(call));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Installs the application CurrentUser principal in the calling thread. */
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Returns the number of successfully committed race branches. */
    private long successes(List<Object> results) {
        return results.stream().filter(OK::equals).count();
    }

    /** Asserts Reservation and its only ReservationRoom share one interval and derived total. */
    private void assertSnapshot(UUID reservation, LocalDate in, LocalDate out, String total) {
        assertEquals(in, jdbc.queryForObject(
                "SELECT check_in_date FROM reservation WHERE id = ?", LocalDate.class, reservation));
        assertEquals(out, jdbc.queryForObject(
                "SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation));
        assertEquals(in, jdbc.queryForObject(
                "SELECT check_in_date FROM reservation_room WHERE reservation_id = ?", LocalDate.class, reservation));
        assertEquals(out, jdbc.queryForObject(
                "SELECT check_out_date FROM reservation_room WHERE reservation_id = ?", LocalDate.class, reservation));
        assertEquals(0, new BigDecimal(total).compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM reservation WHERE id = ?", BigDecimal.class, reservation)));
        assertEquals(0, new BigDecimal(total).compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM reservation_room WHERE reservation_id = ?", BigDecimal.class, reservation)));
        assertEquals(0, new BigDecimal("1000000").compareTo(jdbc.queryForObject(
                "SELECT nightly_rate FROM reservation_room WHERE reservation_id = ?", BigDecimal.class, reservation)));
    }

    /** Creates one active, available Room with configured capacity. */
    private UUID room(String prefix) {
        UUID id = UUID.randomUUID();
        String number = prefix + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return id;
    }

    /** Creates one CONFIRMED Reservation and one correctly priced ReservationRoom. */
    private UUID reservation(LocalDate in, LocalDate out, UUID room, String rate) {
        UUID id = UUID.randomUUID();
        long nights = ChronoUnit.DAYS.between(in, out);
        BigDecimal nightlyRate = new BigDecimal(rate);
        BigDecimal total = nightlyRate.multiply(BigDecimal.valueOf(nights));
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "DC" + id.toString().substring(0, 12), guest, in, out, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, nightlyRate, total, user, user);
        return id;
    }

    /** Counts rows for an invariant query. */
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
