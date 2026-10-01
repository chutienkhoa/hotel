package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.exception.ReservationFieldUpdateException;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.PaymentService;
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
 * Verifies, against real PostgreSQL with real concurrent transactions, that Reservation check-out
 * cannot silently lose a concurrently committed edit to data that stays editable while CHECKED_IN
 * (Booking Contact and Reservation Notes).
 *
 * <p>Check-out writes the whole Reservation row. It locks the Stay first and then loads the
 * Reservation under its OWN {@code PESSIMISTIC_WRITE} lock, which is what the Booking Contact and
 * Notes updates also serialize on. Only two outcomes are therefore possible: the edit commits first
 * and check-out observes it (both succeed, the edit survives), or check-out commits first and the
 * edit is rejected because the Reservation is CHECKED_OUT. Loading the Reservation WITHOUT its lock
 * would add a third, silently wrong outcome — the edit reports success, then check-out's older
 * in-memory snapshot overwrites it on flush — which this test fails on.</p>
 *
 * <p>The surrounding check-out rules (Outstanding must be zero, Room release, assignment closing,
 * inventory coherence afterwards) are asserted here too, so the added row lock cannot pass by
 * weakening them.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CheckOutConcurrencyIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String OK = "ok";
    private static final String RACE_PHONE = "0900000099";
    private static final String RACE_NOTES = "Late departure agreed at the desk";
    private static final int RACE_ITERATIONS = 10;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

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
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHECK_IN', 'CHECK_OUT', 'RECORD_PAYMENT', "
                + "'UPDATE_BOOKING_CONTACT', 'UPDATE_RESERVATION_NOTES')");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "checkout-race." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /**
     * Confirms a Booking Contact update committed concurrently with check-out is never lost: either
     * it commits and survives the check-out that follows it, or it is rejected because check-out
     * already closed the Reservation. A reported success whose value is absent afterwards fails.
     */
    @Test
    void shouldNotLoseABookingContactUpdateCommittedDuringCheckOut() throws Exception {
        int editsCommitted = 0;
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            setUp();
            Settled settled = settledCheckedInStay();

            List<Object> results = race(
                    () -> reservations.checkOut(settled.reservation()),
                    () -> reservations.updateBookingContact(settled.reservation(),
                            new BookingContactUpdateRequest("Race Contact", RACE_PHONE, "race@example.test")));

            assertEquals(OK, results.get(0), "check-out must not fail: " + results);
            assertCompletedCheckOut(settled);
            String phone = jdbc.queryForObject(
                    "SELECT booking_contact_phone FROM reservation WHERE id = ?", String.class, settled.reservation());
            if (OK.equals(results.get(1))) {
                editsCommitted++;
                assertEquals(RACE_PHONE, phone, "a committed Booking Contact update was silently overwritten");
            } else {
                assertTrue(results.get(1) instanceof ReservationFieldUpdateException, results.toString());
                assertNull(phone, "a rejected update must leave the Booking Contact untouched");
            }
        }
        assertTrue(editsCommitted > 0,
                "no iteration committed the edit before check-out, so the lost-update path was never exercised");
    }

    /** Confirms the same guarantee for the Reservation Notes update, the other CHECKED_IN-editable field. */
    @Test
    void shouldNotLoseAReservationNotesUpdateCommittedDuringCheckOut() throws Exception {
        int editsCommitted = 0;
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            setUp();
            Settled settled = settledCheckedInStay();

            List<Object> results = race(
                    () -> reservations.checkOut(settled.reservation()),
                    () -> reservations.updateReservationNotes(
                            settled.reservation(), new NotesUpdateRequest(RACE_NOTES)));

            assertEquals(OK, results.get(0), "check-out must not fail: " + results);
            assertCompletedCheckOut(settled);
            String notes = jdbc.queryForObject(
                    "SELECT notes FROM reservation WHERE id = ?", String.class, settled.reservation());
            if (OK.equals(results.get(1))) {
                editsCommitted++;
                assertEquals(RACE_NOTES, notes, "a committed Reservation Notes update was silently overwritten");
            } else {
                assertTrue(results.get(1) instanceof ReservationFieldUpdateException, results.toString());
                assertNull(notes, "a rejected update must leave the notes untouched");
            }
        }
        assertTrue(editsCommitted > 0,
                "no iteration committed the edit before check-out, so the lost-update path was never exercised");
    }

    /**
     * Confirms the added Reservation row lock did not weaken the approved check-out preconditions:
     * an unsettled folio is still rejected, nothing is mutated, and the Booking Contact edited
     * beforehand is still intact and still editable.
     */
    @Test
    void shouldStillRequireZeroOutstandingAndLeaveEverythingUntouched() {
        UUID reservation = confirmedReservation();
        reservations.checkIn(reservation);
        reservations.updateBookingContact(reservation,
                new BookingContactUpdateRequest("Before Checkout", RACE_PHONE, "before@example.test"));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> reservations.checkOut(reservation));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals("Outstanding balance must be zero for check-out", exception.getReason());
        assertEquals("CHECKED_IN", statusOf(reservation));
        assertEquals("CHECKED_IN", jdbc.queryForObject(
                "SELECT status FROM stay WHERE reservation_id = ?", String.class, reservation));
        assertEquals("OCCUPIED", jdbc.queryForObject(
                "SELECT r.status FROM room r JOIN stay_room_assignment a ON a.room_id = r.id "
                        + "JOIN stay s ON s.id = a.stay_id WHERE s.reservation_id = ?", String.class, reservation));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment a JOIN stay s ON s.id = a.stay_id "
                + "WHERE s.reservation_id = ? AND a.assigned_to IS NULL", reservation));
        assertEquals(RACE_PHONE, jdbc.queryForObject(
                "SELECT booking_contact_phone FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CHECK_OUT' AND entity_id = ?",
                reservation));
    }

    /**
     * Confirms the complete check-out effects and the resulting inventory state are unchanged: the
     * Room is released to DIRTY, the assignment closes at exactly {@code actual_check_out_at}, and
     * the released Room no longer holds the period against a new booking.
     */
    @Test
    void shouldReleaseInventoryCoherentlyAfterCheckOut() {
        Settled settled = settledCheckedInStay();

        reservations.checkOut(settled.reservation());

        assertCompletedCheckOut(settled);
        assertFalse(availability.hasInventoryConflict(settled.room(), today.plusDays(10), today.plusDays(12)),
                "a checked-out Room must not keep holding future inventory");
        assertTrue(availability.conflictedRoomIds(
                        List.of(settled.room()), today.plusDays(10), today.plusDays(12)).isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CHECK_OUT' AND entity_id = ?",
                settled.reservation()));
    }

    /** Asserts every committed effect of a completed check-out for the given fixture. */
    private void assertCompletedCheckOut(Settled settled) {
        assertEquals("CHECKED_OUT", statusOf(settled.reservation()));
        assertEquals("CHECKED_OUT", jdbc.queryForObject(
                "SELECT status FROM stay WHERE reservation_id = ?", String.class, settled.reservation()));
        assertEquals("DIRTY", jdbc.queryForObject(
                "SELECT status FROM room WHERE id = ?", String.class, settled.room()));
        assertEquals(0, count("SELECT COUNT(*) FROM stay_room_assignment a JOIN stay s ON s.id = a.stay_id "
                + "WHERE s.reservation_id = ? AND a.assigned_to IS NULL", settled.reservation()));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment a JOIN stay s ON s.id = a.stay_id "
                + "WHERE s.reservation_id = ? AND a.assigned_to = s.actual_check_out_at", settled.reservation()));
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

    /**
     * Checks in one Reservation and settles its folio in full through the real Payment operation, so
     * the Stay is genuinely eligible for check-out (Outstanding is zero).
     *
     * @return the Reservation, its Stay and its occupied Room
     */
    private Settled settledCheckedInStay() {
        UUID reservation = confirmedReservation();
        reservations.checkIn(reservation);
        UUID stay = jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
        UUID room = jdbc.queryForObject(
                "SELECT room_id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        payments.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("2000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        return new Settled(reservation, stay, room);
    }

    /** Creates one active, available Room with configured capacity. */
    private UUID room() {
        UUID id = UUID.randomUUID();
        String number = "CO-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return id;
    }

    /** Creates one CONFIRMED Reservation arriving today for two nights, with one priced Room line. */
    private UUID confirmedReservation() {
        UUID room = room();
        UUID id = UUID.randomUUID();
        LocalDate in = today;
        LocalDate out = today.plusDays(2);
        BigDecimal nightlyRate = new BigDecimal("1000000");
        long nights = ChronoUnit.DAYS.between(in, out);
        BigDecimal total = nightlyRate.multiply(BigDecimal.valueOf(nights));
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "CO" + id.toString().substring(0, 12), guest, in, out, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, nightlyRate, total, user, user);
        return id;
    }

    /** One checked-in Reservation whose folio is fully settled. */
    private record Settled(UUID reservation, UUID stay, UUID room) {}
}
