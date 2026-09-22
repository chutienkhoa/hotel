package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import java.time.Clock;
import java.time.LocalDate;
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
 * Verifies PostgreSQL lock serialization for the Booking Contact and Reservation Notes controlled updates racing
 * with Check-in. Both updates lock only the Reservation row (no Room lock), so they serialize with Check-in's
 * Rooms-then-Reservation lock order without ever inverting it.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BookingContactAndNotesConcurrencyIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String OK = "ok";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservations;

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
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "contact-race." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /** Confirms a Booking Contact update racing Check-in leaves one complete outcome, never a partial state. */
    @Test
    void shouldSerializeBookingContactUpdateWithCheckIn() throws Exception {
        UUID reservation = reservation(today, today.plusDays(2));

        List<Object> results = race(
                () -> reservations.updateBookingContact(reservation,
                        new BookingContactUpdateRequest("Race Contact", "0900000099", "race@example.test")),
                () -> reservations.checkIn(reservation));

        assertEquals(2, successes(results), results.toString());
        // Both succeed regardless of order (Booking Contact stays editable through CHECKED_IN); the final row must
        // reflect the Booking Contact write and the Reservation must be CHECKED_IN with no partial state.
        String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
        assertEquals("CHECKED_IN", status);
        String phone = jdbc.queryForObject(
                "SELECT booking_contact_phone FROM reservation WHERE id = ?", String.class, reservation);
        assertEquals("0900000099", phone);
        assertEquals(1, count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservation));
    }

    /** Confirms a Reservation Notes update racing Check-in leaves one complete outcome, never a partial state. */
    @Test
    void shouldSerializeNotesUpdateWithCheckIn() throws Exception {
        UUID reservation = reservation(today, today.plusDays(2));

        List<Object> results = race(
                () -> reservations.updateReservationNotes(reservation, new NotesUpdateRequest("Called ahead")),
                () -> reservations.checkIn(reservation));

        assertEquals(2, successes(results), results.toString());
        String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
        assertEquals("CHECKED_IN", status);
        String notes = jdbc.queryForObject("SELECT notes FROM reservation WHERE id = ?", String.class, reservation);
        assertEquals("Called ahead", notes);
        assertEquals(1, count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservation));
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

    /** Counts rows for an invariant query. */
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /** Creates one active, available Room with configured capacity. */
    private UUID room() {
        UUID id = UUID.randomUUID();
        String number = "CR-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return id;
    }

    /** Creates one CONFIRMED Reservation and one correctly priced ReservationRoom. */
    private UUID reservation(LocalDate in, LocalDate out) {
        UUID room = room();
        UUID id = UUID.randomUUID();
        java.math.BigDecimal nightlyRate = new java.math.BigDecimal("1000000");
        long nights = java.time.temporal.ChronoUnit.DAYS.between(in, out);
        java.math.BigDecimal total = nightlyRate.multiply(java.math.BigDecimal.valueOf(nights));
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "CR" + id.toString().substring(0, 12), guest, in, out, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, nightlyRate, total, user, user);
        return id;
    }
}
