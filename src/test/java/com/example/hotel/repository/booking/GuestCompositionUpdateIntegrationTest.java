package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.GuestCompositionUpdateException;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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
 * Verifies the CONFIRMED guest-composition update against PostgreSQL: persistence and audit, capacity and atomicity,
 * safe association replacement under UNIQUE(reservation_id, guest_id), frozen composition after check-in, derived
 * readiness, and serialization with a concurrent Room Reassignment.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GuestCompositionUpdateIntegrationTest {

    private static final UUID SINGLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService service;

    @Autowired
    private ReservationRoomReassignmentService reassignment;

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID primary;

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

    /** Creates the acting user and the primary guest; makes sure the seeded capacities are in place. */
    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "gc." + user);
        primary = guest();
        restoreCapacities();
        authenticate();
    }

    /** Restores the seeded capacities after tests that change them. */
    @AfterEach
    void restoreCapacities() {
        jdbc.update("UPDATE room_type SET capacity = 1 WHERE id = ?", SINGLE_TYPE);
        jdbc.update("UPDATE room_type SET capacity = 2 WHERE id = ?", DOUBLE_TYPE);
    }

    /** Confirms a valid update persists the counts and companions, audits once, and changes nothing else. */
    @Test
    void shouldPersistAuditAndLeaveOtherFieldsUnchanged() {
        UUID companion = guest();
        Response reservation = confirmedReservation(2, 1, LocalDate.now().plusDays(300), room(DOUBLE_TYPE), room(SINGLE_TYPE));
        UUID id = reservation.id();
        Object[] before = snapshot(id);

        service.updateConfirmedGuestComposition(id, request(3, 2, companion));

        assertEquals(3, jdbc.queryForObject("SELECT adult_count FROM reservation WHERE id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT child_count FROM reservation WHERE id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?",
                Integer.class, id, companion));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE_GUEST_COMPOSITION' AND entity_id = ?",
                Integer.class, id));
        assertEquals(Arrays.asList(before), Arrays.asList(snapshot(id)));
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id));
    }

    /** Confirms an over-capacity update fails atomically: nothing changes and no audit entry exists. */
    @Test
    void shouldRejectInsufficientCapacityWithoutAnyChange() {
        UUID companion = guest();
        Response reservation = confirmedReservation(2, 1, LocalDate.now().plusDays(310), room(DOUBLE_TYPE));
        UUID id = reservation.id();
        service.updateConfirmedGuestComposition(id, request(2, 1, companion));

        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(id, request(3, 0, guest())));

        assertEquals(GuestCompositionUpdateException.Reason.INSUFFICIENT_ADULT_CAPACITY, exception.getUpdateReason());
        assertEquals(2, jdbc.queryForObject("SELECT adult_count FROM reservation WHERE id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT child_count FROM reservation WHERE id = ?", Integer.class, id));
        assertEquals(List.of(companion), companions(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE_GUEST_COMPOSITION' AND entity_id = ?",
                Integer.class, id));
    }

    /** Confirms a NULL capacity blocks the update and restoring it lets the same update succeed. */
    @Test
    void shouldBlockUnconfiguredCapacity() {
        Response reservation = confirmedReservation(1, 0, LocalDate.now().plusDays(320), room(DOUBLE_TYPE));
        jdbc.update("UPDATE room_type SET capacity = NULL WHERE id = ?", DOUBLE_TYPE);

        GuestCompositionUpdateException exception = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(reservation.id(), request(1, 2)));

        assertEquals(GuestCompositionUpdateException.Reason.CAPACITY_NOT_CONFIGURED, exception.getUpdateReason());
        assertEquals(0, jdbc.queryForObject("SELECT child_count FROM reservation WHERE id = ?", Integer.class, reservation.id()));
        restoreCapacities();
        service.updateConfirmedGuestComposition(reservation.id(), request(1, 2));
        assertEquals(2, jdbc.queryForObject("SELECT child_count FROM reservation WHERE id = ?", Integer.class, reservation.id()));
    }

    /** Confirms replacing the set keeps retained rows (no unique-constraint clash) and swaps the rest. */
    @Test
    void shouldReplaceAssociationsSafely() {
        UUID a = guest();
        UUID b = guest();
        UUID c = guest();
        Response reservation = confirmedReservation(2, 0, LocalDate.now().plusDays(330), room(DOUBLE_TYPE));
        UUID id = reservation.id();
        service.updateConfirmedGuestComposition(id, request(2, 0, a, b));
        UUID retainedRow = jdbc.queryForObject("SELECT id FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?",
                UUID.class, id, a);

        service.updateConfirmedGuestComposition(id, request(2, 0, a, c));

        assertEquals(retainedRow, jdbc.queryForObject("SELECT id FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?",
                UUID.class, id, a));
        assertEquals(2, count(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?",
                Integer.class, id, b));
        service.updateConfirmedGuestComposition(id, request(2, 0));
        assertEquals(0, count(id));
    }

    /** Confirms the composition is frozen once checked in, and readiness/check-in derive from the updated count. */
    @Test
    void shouldFreezeAfterCheckInAndDeriveReadinessFromTheCurrentCount() {
        Response reservation = confirmedReservation(2, 0, LocalDate.now(), room(DOUBLE_TYPE), room(SINGLE_TYPE));
        UUID id = reservation.id();
        service.updateConfirmedGuestComposition(id, request(3, 0));

        var review = checkInService.review(id);
        assertEquals(3, review.adultCount());
        assertEquals(ArrivalReadinessState.READY, review.readiness().state());

        // Capacity later drops: readiness and check-in both see the current data.
        jdbc.update("UPDATE room_type SET capacity = 1 WHERE id = ?", DOUBLE_TYPE);
        var blocked = checkInService.review(id);
        assertEquals(ArrivalReadinessState.NEEDS_ATTENTION, blocked.readiness().state());
        assertEquals(ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY, blocked.readiness().blockers().get(0).code());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.checkIn(id));
        restoreCapacities();

        assertEquals("CHECKED_IN", service.checkIn(id).status());
        GuestCompositionUpdateException frozen = assertThrows(GuestCompositionUpdateException.class,
                () -> service.updateConfirmedGuestComposition(id, request(1, 0)));
        assertEquals(GuestCompositionUpdateException.Reason.RESERVATION_NOT_CONFIRMED, frozen.getUpdateReason());
        assertEquals(3, jdbc.queryForObject("SELECT adult_count FROM reservation WHERE id = ?", Integer.class, id));
    }

    /**
     * Confirms a composition update and a Room Reassignment racing on the same reservation are serialized: exactly one
     * wins and the final state never has more adults than the capacity of the final room set.
     */
    @Test
    void shouldSerializeWithAConcurrentRoomReassignment() throws Exception {
        UUID doubleRoom = room(DOUBLE_TYPE);
        UUID singleRoom = room(SINGLE_TYPE);
        UUID otherSingle = room(SINGLE_TYPE);
        Response reservation = confirmedReservation(2, 0, LocalDate.now().plusDays(340), doubleRoom, singleRoom);
        UUID id = reservation.id();

        List<Object> results = race(
                () -> service.updateConfirmedGuestComposition(id, request(3, 0)),
                () -> reassignment.reassign(id, doubleRoom, otherSingle));

        long successes = results.stream().filter(r -> r == null).count();
        assertEquals(1, successes, results.toString());
        int adults = jdbc.queryForObject("SELECT adult_count FROM reservation WHERE id = ?", Integer.class, id);
        int capacity = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rt.capacity), 0) FROM reservation_room rr JOIN room r ON r.id = rr.room_id "
                        + "JOIN room_type rt ON rt.id = r.room_type_id WHERE rr.reservation_id = ?", Integer.class, id);
        assertTrue(adults <= capacity, "adults " + adults + " must not exceed capacity " + capacity);
        assertTrue(results.stream().filter(r -> r != null).allMatch(r -> r instanceof GuestCompositionUpdateException
                || r instanceof RoomReassignmentException), results.toString());
        assertNotEquals(0, capacity);
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
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private GuestCompositionUpdateRequest request(int adults, int children, UUID... companions) {
        return new GuestCompositionUpdateRequest(adults, children, List.of(companions));
    }

    private Response confirmedReservation(int adults, int children, LocalDate in, UUID... rooms) {
        Response draft = service.create(new CreateRequest(primary, in, in.plusDays(2), adults, children, BookingSource.DIRECT,
                null, "VND", "keep", Arrays.stream(rooms).map(r -> new RoomRequest(r, new BigDecimal("1000000"))).toList(), List.of()));
        return service.confirm(draft.id());
    }

    private Object[] snapshot(UUID id) {
        return jdbc.queryForObject("SELECT check_in_date::text, check_out_date::text, source, currency, total_amount::text, notes, "
                + "guest_id::text, (SELECT string_agg(room_id::text || nightly_rate::text, ',' ORDER BY room_id) "
                + "FROM reservation_room WHERE reservation_id = reservation.id) FROM reservation WHERE id = ?",
                (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)}, id);
    }

    private List<UUID> companions(UUID reservationId) {
        return jdbc.queryForList("SELECT guest_id FROM reservation_guest WHERE reservation_id = ?", UUID.class, reservationId);
    }

    private int count(UUID reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private UUID guest() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", id, "G" + id.toString().substring(0, 10), user, user);
        return id;
    }

    private UUID room(UUID typeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", id, "GU" + id.toString().substring(0, 8), typeId, user, user);
        return id;
    }
}
