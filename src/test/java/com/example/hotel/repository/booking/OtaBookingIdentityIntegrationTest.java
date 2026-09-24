package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Verifies the approved OTA external booking identity against PostgreSQL: for a non-DIRECT Reservation the pair
 * {@code (source, otaBookingReference)} is permanently unique, the identity is NOT released by a terminal state,
 * different sources are independent, DIRECT is outside the rule, and the partial unique index
 * {@code ux_reservation_ota_identity} (V42) is the authoritative barrier rather than the application check.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OtaBookingIdentityIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private UUID room;
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

    /** Clears bookings and creates the acting user, guest and a bookable room. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_guest");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "ota." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                room, "OTA-" + room.toString().substring(0, 6), SINGLE_ROOM_TYPE_ID, user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /** Confirms each OTA source rejects a second Reservation claiming the same external booking reference. */
    @Test
    void shouldRejectASecondReservationForTheSameSourceAndReference() {
        for (BookingSource source : List.of(BookingSource.AGODA, BookingSource.BOOKING_COM, BookingSource.AIRBNB)) {
            String reference = "REF-" + source.name();
            reservationService.create(request(source, reference));

            ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                    () -> reservationService.create(request(source, reference)),
                    source + " must not accept the same reference twice");

            assertEquals(409, rejected.getStatusCode().value());
            assertEquals(1, count("SELECT COUNT(*) FROM reservation WHERE source = ? AND ota_booking_reference = ?",
                    source.name(), reference), "exactly one reservation holds the identity");
        }
    }

    /** Confirms the same reference text is a different identity under a different source. */
    @Test
    void shouldAllowTheSameReferenceUnderDifferentSources() {
        reservationService.create(request(BookingSource.AGODA, "SAME"));
        reservationService.create(request(BookingSource.BOOKING_COM, "SAME"));
        reservationService.create(request(BookingSource.AIRBNB, "SAME"));

        assertEquals(3, count("SELECT COUNT(*) FROM reservation WHERE ota_booking_reference = ?", "SAME"));
    }

    /** Confirms a CANCELLED Reservation keeps its external identity: the reference can never be re-used. */
    @Test
    void shouldKeepTheIdentityAfterCancellation() {
        UUID reservation = reservationService.create(request(BookingSource.AGODA, "CANCELLED-REF")).id();
        reservationService.confirm(reservation);
        reservationService.cancel(reservation,
                new CancelReservationRequest(CancellationReasonCode.GUEST_REQUEST, null));
        assertEquals("CANCELLED", statusOf(reservation));

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> reservationService.create(request(BookingSource.AGODA, "CANCELLED-REF")));

        assertEquals(409, rejected.getStatusCode().value());
    }

    /** Confirms a NO_SHOW Reservation keeps its external identity. */
    @Test
    void shouldKeepTheIdentityAfterNoShow() {
        UUID reservation = reservationService.create(
                request(BookingSource.BOOKING_COM, "NOSHOW-REF", today.minusDays(2), today.minusDays(1))).id();
        reservationService.confirm(reservation);
        reservationService.noShow(reservation, new NoShowReservationRequest("Guest never arrived"));
        assertEquals("NO_SHOW", statusOf(reservation));

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> reservationService.create(request(BookingSource.BOOKING_COM, "NOSHOW-REF")));

        assertEquals(409, rejected.getStatusCode().value());
    }

    /** Confirms a CHECKED_OUT Reservation keeps its external identity. */
    @Test
    void shouldKeepTheIdentityAfterCheckOut() {
        UUID reservation = reservationService.create(request(BookingSource.AIRBNB, "CHECKEDOUT-REF")).id();
        jdbc.update("UPDATE reservation SET status = 'CHECKED_OUT' WHERE id = ?", reservation);

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> reservationService.create(request(BookingSource.AIRBNB, "CHECKEDOUT-REF")));

        assertEquals(409, rejected.getStatusCode().value());
    }

    /** Confirms DIRECT Reservations are outside the identity rule and remain unaffected. */
    @Test
    void shouldLeaveDirectReservationsOutsideTheIdentityRule() {
        reservationService.create(request(BookingSource.DIRECT, null));
        reservationService.create(request(BookingSource.DIRECT, null));

        assertEquals(2, count("SELECT COUNT(*) FROM reservation WHERE source = 'DIRECT'"));
        assertEquals(2, count("SELECT COUNT(*) FROM reservation WHERE source = 'DIRECT' AND ota_booking_reference IS NULL"),
                "a DIRECT reservation never carries an OTA reference");
    }

    /** Confirms the service re-validates that a non-DIRECT Reservation carries a reference, independently of Bean Validation. */
    @Test
    void shouldRejectANonDirectReservationWithoutAReference() {
        ResponseStatusException blank = assertThrows(ResponseStatusException.class,
                () -> reservationService.create(request(BookingSource.AGODA, "   ")));
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> reservationService.create(request(BookingSource.AGODA, null)));

        assertEquals(400, blank.getStatusCode().value());
        assertEquals(400, missing.getStatusCode().value());
        assertEquals(0, count("SELECT COUNT(*) FROM reservation"));
    }

    /** Confirms correcting a reference cannot take over an identity another Reservation already holds. */
    @Test
    void shouldRejectAnOtaReferenceCorrectionOntoAnExistingIdentity() {
        reservationService.create(request(BookingSource.AGODA, "TAKEN"));
        UUID other = reservationService.create(request(BookingSource.AGODA, "OWN")).id();
        reservationService.confirm(other);

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> reservationService.correctOtaBookingReference(other, new OtaReferenceCorrectionRequest("TAKEN")));

        assertEquals(409, rejected.getStatusCode().value());
        assertEquals("OWN", jdbc.queryForObject(
                "SELECT ota_booking_reference FROM reservation WHERE id = ?", String.class, other));
    }

    /** Confirms re-submitting a Reservation's own current reference is a no-op, not a self-collision. */
    @Test
    void shouldAllowCorrectingAnOtaReferenceToItsOwnCurrentValue() {
        UUID reservation = reservationService.create(request(BookingSource.AGODA, "SELF")).id();
        reservationService.confirm(reservation);

        reservationService.correctOtaBookingReference(reservation, new OtaReferenceCorrectionRequest("SELF"));

        assertEquals("SELF", jdbc.queryForObject(
                "SELECT ota_booking_reference FROM reservation WHERE id = ?", String.class, reservation));
    }

    /**
     * Confirms the database, not the application check, is the authoritative barrier: a direct INSERT that bypasses
     * the service entirely is still rejected by {@code ux_reservation_ota_identity}.
     */
    @Test
    void shouldEnforceTheIdentityInTheDatabaseEvenWhenTheServiceIsBypassed() {
        reservationService.create(request(BookingSource.AGODA, "DB-LEVEL"));

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawReservation("AGODA", "DB-LEVEL", "CONFIRMED"));

        // The same reference under a different source, and a DIRECT row with no reference, still insert cleanly.
        insertRawReservation("BOOKING_COM", "DB-LEVEL", "CONFIRMED");
        insertRawReservation("DIRECT", null, "CONFIRMED");
        insertRawReservation("DIRECT", null, "CONFIRMED");
    }

    /** Confirms two concurrent creations of the same external identity cannot both commit. */
    @Test
    void shouldLetOnlyOneOfTwoConcurrentCreationsWin() throws Exception {
        List<Object> results = race(
                () -> reservationService.create(request(BookingSource.AGODA, "RACE")),
                () -> reservationService.create(request(BookingSource.AGODA, "RACE")));

        long winners = results.stream().filter(result -> !(result instanceof RuntimeException)).count();
        assertEquals(1, winners, "exactly one creation may hold the identity: " + results);
        assertEquals(1, count("SELECT COUNT(*) FROM reservation WHERE source = 'AGODA' AND ota_booking_reference = ?", "RACE"));
    }

    /** Builds a one-room create request for the shared guest and room. */
    private CreateRequest request(BookingSource source, String otaBookingReference) {
        return request(source, otaBookingReference, today.plusDays(10), today.plusDays(12));
    }

    /** Builds a one-room create request for an explicit stay period. */
    private CreateRequest request(BookingSource source, String otaBookingReference, LocalDate in, LocalDate out) {
        return new CreateRequest(guest, in, out, 1, 0, source, otaBookingReference, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of());
    }

    /** Inserts a Reservation row directly, bypassing every application-level check. */
    private void insertRawReservation(String source, String otaBookingReference, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, ota_booking_reference, status, "
                        + "reserved_at, check_in_date, check_out_date, currency, total_amount, created_at, created_by, "
                        + "updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, "OT" + id.toString().substring(0, 12), guest, source, otaBookingReference, status,
                today.plusDays(30), today.plusDays(32), user, user);
    }

    /** Runs two operations simultaneously and returns each outcome or the exception it threw. */
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> operation : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    authenticate();
                    barrier.await();
                    try {
                        return operation.call();
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private String statusOf(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertTrue(value != null);
        return value;
    }
}
