package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.exception.RoomReassignmentException.Reason;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
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
 * Verifies pre-check-in Room reassignment against PostgreSQL: persisted effects, half-open overlap, Arrival Readiness
 * recomputation and lock-based safety under concurrent reassignments.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationRoomReassignmentIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationRoomReassignmentService service;

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private LocalDate checkIn;
    private LocalDate checkOut;

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
        jdbc.update("DELETE FROM audit_log WHERE action = 'REASSIGN_ROOM'");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "re." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        checkIn = LocalDate.now(clock).plusDays(2);
        checkOut = checkIn.plusDays(3);
        authenticate();
    }

    /** Confirms the room changes in the database while rate, total, dates, source and old-room status are kept. */
    @Test
    void shouldPersistReassignmentWithoutRepricingOrTouchingTheOldRoom() {
        UUID oldRoom = room("RA-OLD", "OUT_OF_ORDER");
        UUID target = room("RA-NEW", "AVAILABLE");
        UUID reservation = reservation("CONFIRMED", checkIn, checkOut, "BOOKING_COM", "BK-1");
        UUID line = line(reservation, oldRoom, checkIn, checkOut);

        service.reassign(reservation, oldRoom, target);

        assertEquals(target, jdbc.queryForObject("SELECT room_id FROM reservation_room WHERE id = ?", UUID.class, line));
        assertEquals(0, jdbc.queryForObject("SELECT nightly_rate FROM reservation_room WHERE id = ?",
                java.math.BigDecimal.class, line).compareTo(new java.math.BigDecimal("1000000")));
        assertEquals(checkIn, jdbc.queryForObject("SELECT check_in_date FROM reservation_room WHERE id = ?", LocalDate.class, line));
        assertEquals("BK-1", jdbc.queryForObject("SELECT ota_booking_reference FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals("OUT_OF_ORDER", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, oldRoom));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'REASSIGN_ROOM' AND entity_id = ?",
                Integer.class, reservation));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", Integer.class, reservation));
    }

    /** Confirms the half-open interval: a stay ending on our check-in date is fine, a one-night overlap is not. */
    @Test
    void shouldApplyHalfOpenOverlapSemantics() {
        UUID oldRoom = room("RH-OLD", "OUT_OF_ORDER");
        UUID backToBack = room("RH-B2B", "AVAILABLE");
        UUID overlapping = room("RH-OVL", "AVAILABLE");
        UUID earlier = reservation("CONFIRMED", checkIn.minusDays(2), checkIn, "DIRECT", null);
        line(earlier, backToBack, checkIn.minusDays(2), checkIn);
        UUID clash = reservation("CONFIRMED", checkIn.minusDays(1), checkIn.plusDays(1), "DIRECT", null);
        line(clash, overlapping, checkIn.minusDays(1), checkIn.plusDays(1));
        UUID reservation = reservation("CONFIRMED", checkIn, checkOut, "DIRECT", null);
        line(reservation, oldRoom, checkIn, checkOut);

        RoomReassignmentException rejected = assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation, oldRoom, overlapping));
        assertEquals(Reason.ROOM_UNAVAILABLE, rejected.getReason());
        assertEquals(oldRoom, roomOfLine(reservation));

        service.reassign(reservation, oldRoom, backToBack);
        assertEquals(backToBack, roomOfLine(reservation));
    }

    /** Confirms a non-CONFIRMED reservation and non-available targets are rejected against the real schema. */
    @Test
    void shouldRejectIneligibleStatesAndTargets() {
        UUID oldRoom = room("RI-OLD", "AVAILABLE");
        UUID dirty = room("RI-DIRTY", "DIRTY");
        UUID reservation = reservation("CONFIRMED", checkIn, checkOut, "DIRECT", null);
        line(reservation, oldRoom, checkIn, checkOut);
        UUID cancelled = reservation("CANCELLED", checkIn, checkOut, "DIRECT", null);
        UUID cancelledOld = room("RI-CXL", "AVAILABLE");
        line(cancelled, cancelledOld, checkIn, checkOut);

        assertEquals(Reason.ROOM_UNAVAILABLE, assertThrows(RoomReassignmentException.class,
                () -> service.reassign(reservation, oldRoom, dirty)).getReason());
        assertEquals(Reason.RESERVATION_STATE_CHANGED, assertThrows(RoomReassignmentException.class,
                () -> service.reassign(cancelled, cancelledOld, dirty)).getReason());
        assertEquals(oldRoom, roomOfLine(reservation));
    }

    /** Confirms Arrival Readiness is recomputed from the updated reservation and becomes READY. */
    @Test
    void shouldRecomputeArrivalReadinessAfterReassignment() {
        checkIn = LocalDate.now(clock);
        checkOut = checkIn.plusDays(2);
        UUID oldRoom = room("RR-OLD", "OUT_OF_ORDER");
        UUID target = room("RR-NEW", "AVAILABLE");
        UUID reservation = reservation("CONFIRMED", checkIn, checkOut, "DIRECT", null);
        line(reservation, oldRoom, checkIn, checkOut);

        var before = checkInService.review(reservation).readiness();
        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, before.state());
        assertEquals(ArrivalIssueCode.ROOM_OUT_OF_ORDER, before.blockers().get(0).code());

        service.reassign(reservation, oldRoom, target);

        var after = checkInService.review(reservation);
        assertEquals(ArrivalReadinessState.READY, after.readiness().state());
        assertTrue(after.eligibleForCheckIn());
    }

    /** Confirms two concurrent replacements of the same line yield exactly one winner and a consistent line. */
    @Test
    void shouldSerializeConcurrentReplacementsOfTheSameLine() throws Exception {
        UUID oldRoom = room("RC-OLD", "OUT_OF_ORDER");
        UUID first = room("RC-A", "AVAILABLE");
        UUID second = room("RC-B", "AVAILABLE");
        UUID reservation = reservation("CONFIRMED", checkIn, checkOut, "DIRECT", null);
        line(reservation, oldRoom, checkIn, checkOut);

        List<Object> results = race(
                () -> service.reassign(reservation, oldRoom, first),
                () -> service.reassign(reservation, oldRoom, second));

        assertEquals(1, results.stream().filter(result -> result == null).count(), results.toString());
        assertEquals(1, results.stream().filter(RoomReassignmentException.class::isInstance).count(), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id = ?", Integer.class, reservation));
        UUID winner = roomOfLine(reservation);
        assertTrue(winner.equals(first) || winner.equals(second));
    }

    /** Confirms two reservations racing for the same replacement room cannot both get it for overlapping dates. */
    @Test
    void shouldNotDoubleBookTheSameReplacementRoom() throws Exception {
        UUID oldA = room("RD-OLDA", "OUT_OF_ORDER");
        UUID oldB = room("RD-OLDB", "OUT_OF_ORDER");
        UUID target = room("RD-TARGET", "AVAILABLE");
        UUID reservationA = reservation("CONFIRMED", checkIn, checkOut, "DIRECT", null);
        line(reservationA, oldA, checkIn, checkOut);
        UUID reservationB = reservation("CONFIRMED", checkIn.plusDays(1), checkOut.plusDays(1), "DIRECT", null);
        line(reservationB, oldB, checkIn.plusDays(1), checkOut.plusDays(1));

        List<Object> results = race(
                () -> service.reassign(reservationA, oldA, target),
                () -> service.reassign(reservationB, oldB, target));

        assertEquals(1, results.stream().filter(result -> result == null).count(), results.toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_room WHERE room_id = ?", Integer.class, target));
    }

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
                    return null;
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
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "staff"), null, List.of()));
    }

    private UUID roomOfLine(UUID reservation) {
        return jdbc.queryForObject("SELECT room_id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
    }

    private UUID room(String number, String status) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET status = EXCLUDED.status",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, status, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID reservation(String status, LocalDate in, LocalDate out, String source, String otaReference) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, ota_booking_reference, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, "RA" + id.toString().substring(0, 12), guest, source, otaReference, status, in, out, user, user);
        return id;
    }

    private UUID line(UUID reservation, UUID room, LocalDate in, LocalDate out) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1000000, 3000000, now(), ?, now(), ?)",
                id, reservation, room, in, out, user, user);
        return id;
    }
}
