package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.response.RoomChangeCandidateResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.RoomChangeService;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
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
 * Verifies Room Change-aware booking availability against PostgreSQL: the lifecycle-aware overlap primitive (CONFIRMED
 * via ReservationRoom, CHECKED_IN via the actual StayRoomAssignments), Room Change target and candidate checks,
 * Confirm, room lookups, and the Confirm-versus-Room-Change race.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomChangeInventoryIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RoomChangeService roomChangeService;

    @Autowired
    private com.example.hotel.service.room.RoomService roomService;

    @Autowired
    private RoomAvailabilityService availability;

    @Autowired
    private RoomRepository roomRepository;

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
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHANGE_ROOM', 'CONFIRM_RESERVATION')");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "inv." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /** Confirms the core bug is fixed: after A to B the guest's dates block B and no longer block A. */
    @Test
    void shouldBlockTheNewRoomAndReleaseTheOldRoomAfterRoomChange() {
        UUID a = room("IV-A", "AVAILABLE");
        UUID b = room("IV-B", "AVAILABLE");
        UUID stay = checkedIn(a, today, today.plusDays(3));

        roomChangeService.changeRoom(reservationOf(stay), a, change(b));

        assertTrue(availability.hasInventoryConflict(b, today.plusDays(1), today.plusDays(2)));
        assertFalse(availability.hasInventoryConflict(a, today.plusDays(1), today.plusDays(2)));

        UUID r2OnB = draft(b, today.plusDays(1), today.plusDays(2));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> reservationService.confirm(r2OnB)).getStatusCode());
        assertEquals("DRAFT", statusOf(r2OnB));

        UUID r3OnA = draft(a, today.plusDays(1), today.plusDays(2));
        assertEquals("CONFIRMED", reservationService.confirm(r3OnA).status());
    }

    /** Confirms lookups follow the same primitive: B is not offered, the released A is. */
    @Test
    void shouldReflectRoomChangeInBookableLookups() {
        UUID a = room("IV-LA", "AVAILABLE");
        UUID b = room("IV-LB", "AVAILABLE");
        UUID stay = checkedIn(a, today, today.plusDays(3));
        roomChangeService.changeRoom(reservationOf(stay), a, change(b));

        List<UUID> offered = availability.bookableRoomsForPeriod(today.plusDays(1), today.plusDays(2)).stream()
                .map(RoomLookupResponse::id).toList();

        assertTrue(offered.contains(a));
        assertFalse(offered.contains(b));
    }

    /** Confirms the same guest can return B to A (no self-conflict on the released room) and A to B to C is correct. */
    @Test
    void shouldAllowReturnAndMultiHopChanges() {
        UUID a = room("IV-RA", "AVAILABLE");
        UUID b = room("IV-RB", "AVAILABLE");
        UUID c = room("IV-RC", "AVAILABLE");
        UUID stay = checkedIn(a, today, today.plusDays(4));
        UUID reservation = reservationOf(stay);

        roomChangeService.changeRoom(reservation, a, change(b));
        markCleaned(a);
        roomChangeService.changeRoom(reservation, b, change(a));
        markCleaned(b);
        roomChangeService.changeRoom(reservation, a, change(b));
        markCleaned(a);
        roomChangeService.changeRoom(reservation, b, change(c));

        LocalDate in = today.plusDays(1);
        LocalDate out = today.plusDays(3);
        assertFalse(availability.hasInventoryConflict(a, in, out));
        assertFalse(availability.hasInventoryConflict(b, in, out));
        assertTrue(availability.hasInventoryConflict(c, in, out));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? AND assigned_to IS NULL", Integer.class, stay));
    }

    /** Confirms the Room Change candidate list excludes rooms held by CONFIRMED bookings and by other stays. */
    @Test
    void shouldFilterRoomChangeCandidatesWithThePrimitive() {
        UUID a = room("IV-CA", "AVAILABLE");
        UUID free = room("IV-CFREE", "AVAILABLE");
        UUID confirmedElsewhere = room("IV-CCONF", "AVAILABLE");
        UUID heldByOtherStay = room("IV-CSTAY", "OCCUPIED");
        UUID underMaintenance = room("IV-CMAINT", "MAINTENANCE");
        UUID outOfOrder = room("IV-COOO", "OUT_OF_ORDER");
        UUID stay = checkedIn(a, today, today.plusDays(3));
        UUID other = confirmed(confirmedElsewhere, today.plusDays(1), today.plusDays(2));
        checkedIn(heldByOtherStay, today, today.plusDays(2));

        List<UUID> candidates = roomChangeService.candidateRooms(reservationOf(stay), a).stream()
                .map(RoomChangeCandidateResponse::id).toList();

        assertTrue(candidates.contains(free));
        assertFalse(candidates.contains(confirmedElsewhere));
        assertFalse(candidates.contains(heldByOtherStay));
        assertFalse(candidates.contains(underMaintenance), "MAINTENANCE rooms must be excluded like OUT_OF_ORDER");
        assertFalse(candidates.contains(outOfOrder));
        assertEquals("CONFIRMED", statusOf(other));
    }

    /**
     * Confirms the maintenance/out-of-order warning surfaces only upcoming CONFIRMED reservations for the
     * Room, excluding DRAFT and CANCELLED, and never touches Reservation/ReservationRoom.
     */
    @Test
    void shouldWarnOnlyAboutUpcomingConfirmedReservationsForTheRoom() {
        UUID a = room("IV-WA", "AVAILABLE");
        UUID upcoming = confirmed(a, today.plusDays(5), today.plusDays(7));
        draft(a, today.plusDays(10), today.plusDays(12));
        reservation("CANCELLED", a, today.plusDays(1), today.plusDays(2));
        String upcomingNumber = jdbc.queryForObject(
                "SELECT reservation_number FROM reservation WHERE id = ?", String.class, upcoming);

        List<com.example.hotel.dto.room.response.AffectedReservationResponse> affected =
                roomService.findUpcomingAffectedReservations(a);

        assertEquals(1, affected.size());
        assertEquals(upcomingNumber, affected.get(0).reservationNumber());
        assertEquals(today.plusDays(5), affected.get(0).checkInDate());
        assertEquals(today.plusDays(7), affected.get(0).checkOutDate());
        assertEquals("CONFIRMED", statusOf(upcoming), "the warning must never change the Reservation");
    }

    /** Confirms a CONFIRMED reservation blocks overlapping dates but not adjacent ones (half-open intervals). */
    @Test
    void shouldKeepConfirmedSemanticsAndAllowAdjacentIntervals() {
        UUID r = room("IV-CF", "AVAILABLE");
        confirmed(r, today.plusDays(5), today.plusDays(7));

        assertTrue(availability.hasInventoryConflict(r, today.plusDays(6), today.plusDays(8)));
        assertTrue(availability.hasInventoryConflict(r, today.plusDays(4), today.plusDays(6)));
        assertFalse(availability.hasInventoryConflict(r, today.plusDays(3), today.plusDays(5)));
        assertFalse(availability.hasInventoryConflict(r, today.plusDays(7), today.plusDays(9)));
    }

    /** Confirms a CHECKED_IN reservation's own ReservationRoom is not counted once its stay moved to another room. */
    @Test
    void shouldNotCountTheCheckedInReservationRoomLine() {
        UUID a = room("IV-NA", "AVAILABLE");
        UUID b = room("IV-NB", "AVAILABLE");
        UUID stay = checkedIn(a, today, today.plusDays(3));
        roomChangeService.changeRoom(reservationOf(stay), a, change(b));

        assertEquals(a, jdbc.queryForObject("SELECT rr.room_id FROM reservation_room rr JOIN stay s "
                + "ON s.reservation_id = rr.reservation_id WHERE s.id = ?", UUID.class, stay), "line is immutable");
        assertFalse(availability.hasInventoryConflict(a, today.plusDays(1), today.plusDays(3)));
    }

    /** Confirms an overdue open assignment protects only the current hotel night. */
    @Test
    void shouldProtectOnlyTheCurrentNightForAnOverdueStay() {
        UUID a = room("IV-OD", "OCCUPIED");
        checkedIn(a, today.minusDays(3), today.minusDays(1));

        assertTrue(availability.hasInventoryConflict(a, today, today.plusDays(1)));
        assertFalse(availability.hasInventoryConflict(a, today.plusDays(1), today.plusDays(2)));
    }

    /** Confirms a checked-out stay leaves no blocker behind. */
    @Test
    void shouldNotBlockAfterCheckOut() {
        UUID a = room("IV-CO", "AVAILABLE");
        UUID stay = checkedIn(a, today, today.plusDays(3));
        jdbc.update("UPDATE stay_room_assignment SET assigned_to = now() WHERE stay_id = ?", stay);
        jdbc.update("UPDATE stay SET status = 'CHECKED_OUT', actual_check_out_at = now() WHERE id = ?", stay);
        jdbc.update("UPDATE reservation SET status = 'CHECKED_OUT' WHERE id = ?", reservationOf(stay));

        assertFalse(availability.hasInventoryConflict(a, today.plusDays(1), today.plusDays(3)));
    }

    /** Confirms closed-assignment boundaries with a fixed clock and zone: [date(from), date(to)) in hotel dates. */
    @Test
    void shouldConvertClosedAssignmentInstantsToHotelDates() {
        LocalDate d20 = LocalDate.of(2031, 9, 20);
        UUID a = room("IV-BD", "AVAILABLE");
        UUID stay = checkedIn(a, d20, d20.plusDays(4));
        Instant from = d20.atTime(14, 0).atZone(ZONE).toInstant();
        Instant to = d20.plusDays(1).atTime(0, 30).atZone(ZONE).toInstant();
        jdbc.update("UPDATE stay_room_assignment SET assigned_from = ?, assigned_to = ? WHERE stay_id = ?",
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), stay);
        RoomAvailabilityService fixed = new RoomAvailabilityService(roomRepository,
                Clock.fixed(d20.plusDays(1).atTime(9, 0).atZone(ZONE).toInstant(), ZONE));

        assertTrue(fixed.hasInventoryConflict(a, d20, d20.plusDays(1)), "night of 20th");
        assertFalse(fixed.hasInventoryConflict(a, d20.plusDays(1), d20.plusDays(2)),
                "closed at 00:30 on the 21st covers [20th, 21st): the 21st night is free");
        assertFalse(fixed.hasInventoryConflict(a, d20.plusDays(2), d20.plusDays(3)));
        assertFalse(fixed.hasInventoryConflict(a, d20.minusDays(2), d20));
    }

    /**
     * Regression coverage for the {@code checkedIn} fixture's time-of-day defect: a same-day check-in must be
     * closeable at any hour of that same day, not merely whatever real wall-clock hour the suite happens to run
     * at. This proves the fixture with two EXPLICIT deterministic closing instants, one well before 14:00
     * Asia/Ho_Chi_Minh and one well after, rather than relying on the actual execution time. If the fixture ever
     * regresses to a fixed hour (e.g. {@code atTime(14, 0)}) instead of {@code atStartOfDay(ZONE)}, the morning
     * assertion below fails exactly as the original bug did: {@code assigned_to (08:00) < assigned_from (14:00)}
     * violates {@code stay_room_assignment_interval}.
     */
    @Test
    void shouldCloseASameDayAssignmentAtAnyHourOfTheCheckInDay() {
        UUID morningRoom = room("IV-AM", "AVAILABLE");
        UUID morningStay = checkedIn(morningRoom, today, today.plusDays(2));
        Instant morningClose = today.atTime(8, 0).atZone(ZONE).toInstant();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> jdbc.update("UPDATE stay_room_assignment SET assigned_to = ? WHERE stay_id = ?",
                        java.sql.Timestamp.from(morningClose), morningStay),
                "a same-day assignment must close cleanly even before 14:00 ICT");

        UUID afternoonRoom = room("IV-PM", "AVAILABLE");
        UUID afternoonStay = checkedIn(afternoonRoom, today, today.plusDays(2));
        Instant afternoonClose = today.atTime(16, 0).atZone(ZONE).toInstant();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> jdbc.update("UPDATE stay_room_assignment SET assigned_to = ? WHERE stay_id = ?",
                        java.sql.Timestamp.from(afternoonClose), afternoonStay),
                "and just as cleanly in the afternoon");
    }

    /** Confirms Confirm and Room Change never both win for the same room and dates (repeated race). */
    @Test
    void shouldNeverCreateAConflictWhenConfirmRacesRoomChange() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            setUp();
            UUID a = room("IV-RACE-A", "AVAILABLE");
            UUID b = room("IV-RACE-B", "AVAILABLE");
            UUID stay = checkedIn(a, today, today.plusDays(3));
            UUID r2 = draft(b, today.plusDays(1), today.plusDays(2));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> reservationService.confirm(r2),
                    () -> roomChangeService.changeRoom(reservation, a, change(b)));

            long winners = results.stream().filter(result -> !(result instanceof RuntimeException)).count();
            assertEquals(1, winners, results.toString());
            boolean r2Confirmed = "CONFIRMED".equals(statusOf(r2));
            boolean moved = jdbc.queryForObject("SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? "
                    + "AND room_id = ? AND assigned_to IS NULL", Integer.class, stay, b) > 0;
            assertTrue(r2Confirmed ^ moved, "exactly one of the two may hold room B");
        }
    }

    /** Simulates Housekeeping having cleaned a released room (status is separate from date availability). */
    private void markCleaned(UUID room) {
        jdbc.update("UPDATE room SET status = 'AVAILABLE' WHERE id = ?", room);
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

    private RoomChangeRequest change(UUID target) {
        return new RoomChangeRequest(target, RoomChangeReason.GUEST_REQUEST, null);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "staff"), null, List.of()));
    }

    private String statusOf(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
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

    private UUID confirmed(UUID room, LocalDate in, LocalDate out) {
        return reservation("CONFIRMED", room, in, out);
    }

    private UUID draft(UUID room, LocalDate in, LocalDate out) {
        return reservation("DRAFT", room, in, out);
    }

    private UUID reservation(String status, UUID room, LocalDate in, LocalDate out) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, "IV" + id.toString().substring(0, 12), guest, status, in, out, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1000000, 1000000, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, user, user);
        return id;
    }

    /** Seeds a CHECKED_IN reservation, its Stay and an open assignment on the room; returns the Stay id. */
    private UUID checkedIn(UUID room, LocalDate in, LocalDate out) {
        UUID reservation = reservation("CHECKED_IN", room, in, out);
        UUID stay = UUID.randomUUID();
        // Midnight of the check-in day, never a fixed wall-clock hour: every caller passes in <= today, so this is
        // always <= any later real Instant.now(clock) a production close (Room Change, checkout) computes the same
        // day - unlike a fixed hour such as 14:00, which is in the future whenever the suite runs before it.
        Instant from = in.atStartOfDay(ZONE).toInstant();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', ?, now(), ?, now(), ?)", stay, reservation, java.sql.Timestamp.from(from), user, user);
        UUID line = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, line, java.sql.Timestamp.from(from), user, user);
        jdbc.update("UPDATE room SET status = 'OCCUPIED' WHERE id = ?", room);
        return stay;
    }
}
