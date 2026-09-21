package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Verifies against PostgreSQL that Accompanying Guests persist as join rows, that the unique and foreign-key
 * constraints hold, that a draft replace keeps retained rows, and that loading them is independent of their number.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationAccompanyingGuestsPersistenceIntegrationTest {

    private static final UUID DOUBLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService service;

    @Autowired
    private ReservationQueryService queryService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID primary;
    private UUID room;

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

    /** Creates the acting user, a primary guest and a room. */
    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "ag." + user);
        primary = guest("P");
        room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", room, "AG" + room.toString().substring(0, 6),
                DOUBLE_ROOM_TYPE_ID, user, user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Confirms join rows persist, a replace keeps the retained row, and removal deletes only the dropped one. */
    @Test
    void shouldPersistReplaceAndRemoveJoinRows() {
        UUID b = guest("B");
        UUID c = guest("C");
        LocalDate in = LocalDate.now().plusDays(50);
        Response created = service.create(request(in, List.of(b)));
        UUID retainedRow = jdbc.queryForObject(
                "SELECT id FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?", UUID.class, created.id(), b);

        service.updateDraft(created.id(), request(in, List.of(b, c)));

        assertEquals(2, count(created.id()));
        assertEquals(retainedRow, jdbc.queryForObject(
                "SELECT id FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?", UUID.class, created.id(), b));

        service.updateDraft(created.id(), request(in, List.of(c)));
        assertEquals(1, count(created.id()));

        service.updateDraft(created.id(), request(in, List.of()));
        assertEquals(0, count(created.id()));
        assertEquals(primary, jdbc.queryForObject("SELECT guest_id FROM reservation WHERE id = ?", UUID.class, created.id()));
    }

    /** Confirms UNIQUE(reservation_id, guest_id) and both foreign keys are enforced by the database. */
    @Test
    void shouldEnforceUniqueAndForeignKeyConstraints() {
        UUID b = guest("B2");
        Response created = service.create(request(LocalDate.now().plusDays(60), List.of(b)));

        assertThrows(DataIntegrityViolationException.class, () -> insertRow(created.id(), b));
        assertThrows(DataIntegrityViolationException.class, () -> insertRow(UUID.randomUUID(), guest("B3")));
        assertThrows(DataIntegrityViolationException.class, () -> insertRow(created.id(), UUID.randomUUID()));
        assertEquals(1, count(created.id()));
    }

    /** Confirms a Reservation without join rows (as every pre-existing one) is valid and loads with no companions. */
    @Test
    void shouldTreatExistingReservationsAsHavingNoAccompanyingGuests() {
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, "
                        + "check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                legacy, "AG" + legacy.toString().substring(0, 12), primary, LocalDate.now().plusDays(70),
                LocalDate.now().plusDays(72), user, user);

        assertTrue(queryService.findAccompanyingGuests(legacy).isEmpty());
        assertEquals(0, count(legacy));
    }

    /** Confirms a Guest may be Primary on one Reservation and Accompanying on another. */
    @Test
    void shouldAllowAGuestToBePrimaryAndAccompanyingOnDifferentReservations() {
        UUID other = guest("O");
        service.create(request(LocalDate.now().plusDays(80), List.of(other)));
        Response second = createFor(other, LocalDate.now().plusDays(90), List.of(primary));

        assertEquals(1, count(second.id()));
    }

    /** Confirms loading accompanying guests costs one statement whether there is one guest or five. */
    @Test
    void shouldLoadAccompanyingGuestsWithOneStatementRegardlessOfCount() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        UUID one = guest("S1");
        Response few = service.create(request(LocalDate.now().plusDays(100), List.of(one)));
        List<UUID> many = List.of(guest("M1"), guest("M2"), guest("M3"), guest("M4"), guest("M5"));
        Response lots = service.create(request(LocalDate.now().plusDays(110), many));

        statistics.clear();
        assertEquals(1, queryService.findAccompanyingGuests(few.id()).size());
        long fewStatements = statistics.getPrepareStatementCount();
        statistics.clear();
        assertEquals(5, queryService.findAccompanyingGuests(lots.id()).size());
        long manyStatements = statistics.getPrepareStatementCount();

        assertEquals(1, fewStatements);
        assertEquals(1, manyStatements);
    }

    private Response createFor(UUID primaryGuest, LocalDate in, List<UUID> companions) {
        return service.create(new CreateRequest(primaryGuest, in, in.plusDays(2), 2, 0, BookingSource.DIRECT, null, "VND",
                null, List.of(new RoomRequest(room, new BigDecimal("1000000"))), companions));
    }

    private CreateRequest request(LocalDate in, List<UUID> companions) {
        return new CreateRequest(primary, in, in.plusDays(2), 2, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), companions);
    }

    private int count(UUID reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private void insertRow(UUID reservationId, UUID guestId) {
        jdbc.update("INSERT INTO reservation_guest (id, reservation_id, guest_id, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, now(), ?, now(), ?)", UUID.randomUUID(), reservationId, guestId, user, user);
    }

    private UUID guest(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", id, "G" + id.toString().substring(0, 8) + suffix, user, user);
        return id;
    }
}
