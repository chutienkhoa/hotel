package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.room.RoomAvailabilityService;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the hotel's most important inventory invariant — no double-booking (specification §36 and
 * critical invariant 2) — under TRUE concurrency against real PostgreSQL, rather than by reasoning
 * about the lock order.
 *
 * <p>Two DRAFT Reservations hold the SAME Room over OVERLAPPING dates, which is legal while both are
 * drafts, and both are confirmed at once from separate transactions released by a shared barrier.
 * Confirm locks the Room rows ({@code PESSIMISTIC_WRITE}) in stable identifier order, then the
 * Reservation row, and only then runs the lifecycle-aware availability check while still holding the
 * Room lock; the two transactions therefore contend on the same Room row. Exactly one confirmation
 * may succeed, the other must fail through the existing approved conflict path, and the surviving
 * inventory must be coherent when re-read through the real availability query.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationConfirmConcurrencyIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String OK = "ok";
    private static final int RACE_ITERATIONS = 12;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservations;

    @Autowired
    private RoomAvailabilityService availability;

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
        jdbc.update("DELETE FROM audit_log WHERE action = 'CONFIRM'");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "confirm-race." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /**
     * Confirms that two genuinely concurrent confirmations of the same Room over overlapping dates
     * produce exactly one CONFIRMED Reservation, that the loser stays DRAFT and writes no CONFIRM
     * audit entry, and that the inventory read back through the real availability query holds the
     * Room for exactly the winning interval.
     */
    @Test
    void shouldConfirmOnlyOneOfTwoOverlappingReservationsForTheSameRoom() throws Exception {
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            setUp();
            UUID room = room();
            // Overlapping, non-identical intervals: [+1,+4) and [+3,+6) share the night of day +3.
            LocalDate firstIn = today.plusDays(1);
            LocalDate firstOut = today.plusDays(4);
            LocalDate secondIn = today.plusDays(3);
            LocalDate secondOut = today.plusDays(6);
            UUID first = draftReservation(room, firstIn, firstOut);
            UUID second = draftReservation(room, secondIn, secondOut);

            List<Object> results = race(
                    () -> reservations.confirm(first),
                    () -> reservations.confirm(second));

            assertEquals(1, results.stream().filter(OK::equals).count(),
                    "exactly one confirmation may succeed: " + results);
            assertRejectedThroughTheApprovedConflictPath(results);
            assertEquals(1, count("SELECT COUNT(*) FROM reservation WHERE status = 'CONFIRMED' AND id IN (?, ?)",
                    first, second));
            assertEquals(1, count("SELECT COUNT(*) FROM reservation WHERE status = 'DRAFT' AND id IN (?, ?)",
                    first, second));

            boolean firstWon = OK.equals(results.get(0));
            UUID winner = firstWon ? first : second;
            UUID loser = firstWon ? second : first;
            LocalDate wonIn = firstWon ? firstIn : secondIn;
            LocalDate wonOut = firstWon ? firstOut : secondOut;
            assertEquals("CONFIRMED", statusOf(winner));
            assertEquals("DRAFT", statusOf(loser));
            assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CONFIRM' AND entity_id = ?", winner));
            assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CONFIRM' AND entity_id = ?", loser));
            assertInventoryHeldForExactly(room, wonIn, wonOut);
        }
    }

    /**
     * Confirms the invariant holds for the strictest case as well: two concurrent confirmations of
     * the same Room over the IDENTICAL interval, where neither request can be distinguished by its
     * dates.
     */
    @Test
    void shouldConfirmOnlyOneOfTwoIdenticalIntervalReservationsForTheSameRoom() throws Exception {
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            setUp();
            UUID room = room();
            LocalDate in = today.plusDays(2);
            LocalDate out = today.plusDays(5);
            UUID first = draftReservation(room, in, out);
            UUID second = draftReservation(room, in, out);

            List<Object> results = race(
                    () -> reservations.confirm(first),
                    () -> reservations.confirm(second));

            assertEquals(1, results.stream().filter(OK::equals).count(),
                    "exactly one confirmation may succeed: " + results);
            assertRejectedThroughTheApprovedConflictPath(results);
            assertEquals(1, count("SELECT COUNT(*) FROM reservation WHERE status = 'CONFIRMED' AND id IN (?, ?)",
                    first, second));
            assertInventoryHeldForExactly(room, in, out);
        }
    }

    /**
     * Confirms that two concurrent confirmations for the same dates but DIFFERENT Rooms are not
     * serialized into a false conflict: the guard protects inventory, it does not reject legitimate
     * parallel bookings.
     */
    @Test
    void shouldConfirmBothConcurrentReservationsWhenTheRoomsDiffer() throws Exception {
        UUID firstRoom = room();
        UUID secondRoom = room();
        LocalDate in = today.plusDays(1);
        LocalDate out = today.plusDays(3);
        UUID first = draftReservation(firstRoom, in, out);
        UUID second = draftReservation(secondRoom, in, out);

        List<Object> results = race(
                () -> reservations.confirm(first),
                () -> reservations.confirm(second));

        assertEquals(2, results.stream().filter(OK::equals).count(), results.toString());
        assertEquals("CONFIRMED", statusOf(first));
        assertEquals("CONFIRMED", statusOf(second));
        assertInventoryHeldForExactly(firstRoom, in, out);
        assertInventoryHeldForExactly(secondRoom, in, out);
    }

    /**
     * Asserts the losing branch failed through the existing approved conflict path — the established
     * {@code ResponseStatusException} carrying HTTP 409 — rather than through an unexpected error.
     *
     * @param results the two race outcomes
     */
    private void assertRejectedThroughTheApprovedConflictPath(List<Object> results) {
        Object rejection = results.stream().filter(result -> !OK.equals(result)).findFirst().orElseThrow();
        assertTrue(rejection instanceof ResponseStatusException, "unexpected failure type: " + rejection);
        ResponseStatusException exception = (ResponseStatusException) rejection;
        assertEquals(409, exception.getStatusCode().value(), String.valueOf(exception.getReason()));
        assertTrue("Room is already booked for these dates".equals(exception.getReason())
                        || "Invalid reservation state transition".equals(exception.getReason()),
                "unexpected conflict reason: " + exception.getReason());
    }

    /**
     * Verifies through the real availability query that the Room is held for exactly the confirmed
     * interval: every night inside it conflicts, and the nights immediately outside it do not.
     *
     * @param room the Room that was booked
     * @param in inclusive first confirmed night
     * @param out exclusive end of the confirmed interval
     */
    private void assertInventoryHeldForExactly(UUID room, LocalDate in, LocalDate out) {
        for (LocalDate night = in; night.isBefore(out); night = night.plusDays(1)) {
            assertTrue(availability.hasInventoryConflict(room, night, night.plusDays(1)),
                    "night " + night + " must be held by the confirmed reservation");
            assertFalse(availability.bookableRoomsForPeriod(night, night.plusDays(1)).stream()
                            .anyMatch(offered -> offered.id().equals(room)),
                    "a held Room must not be offered for booking on " + night);
        }
        // The interval is half-open, so the departure night itself is free, as is the night before arrival.
        assertFalse(availability.hasInventoryConflict(room, out, out.plusDays(1)),
                "the departure date must stay bookable");
        assertFalse(availability.hasInventoryConflict(room, in.minusDays(1), in),
                "the night before arrival must stay bookable");
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

    /** Returns the Reservation status currently committed in the database. */
    private String statusOf(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
    }

    /** Counts rows for an invariant query. */
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /** Creates one active, available Room with configured capacity. */
    private UUID room() {
        UUID id = UUID.randomUUID();
        String number = "CF-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return id;
    }

    /** Creates one DRAFT Reservation with a single correctly priced Room line for the given interval. */
    private UUID draftReservation(UUID room, LocalDate in, LocalDate out) {
        UUID id = UUID.randomUUID();
        BigDecimal nightlyRate = new BigDecimal("1000000");
        long nights = ChronoUnit.DAYS.between(in, out);
        BigDecimal total = nightlyRate.multiply(BigDecimal.valueOf(nights));
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'DRAFT', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "CF" + id.toString().substring(0, 12), guest, in, out, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, nightlyRate, total, user, user);
        return id;
    }
}
