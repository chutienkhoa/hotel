package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.RoomReassignmentException;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.AdultCapacityRules;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import com.example.hotel.service.booking.ReservationService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the adult-capacity rule against PostgreSQL with the seeded RoomTypes (SINGLE 1, DOUBLE 2, ...): Confirm,
 * Check-in and pre-check-in Room Reassignment enforce it on the current data, a nullable capacity blocks, failures
 * change nothing, and capacity evaluation loads RoomTypes in one batch.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AdultCapacityIntegrationTest {

    private static final UUID SINGLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID TRIPLE_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService service;

    @Autowired
    private ReservationRoomReassignmentService reassignment;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private UUID user;
    private UUID guest;

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

    /** Creates the acting user and a guest, and makes sure the seeded capacities are in place. */
    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "ac." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 10), user, user);
        restoreCapacities();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Restores the seeded capacities after tests that change them. */
    @AfterEach
    void restoreCapacities() {
        jdbc.update("UPDATE room_type SET capacity = 1 WHERE id = ?", SINGLE_TYPE);
        jdbc.update("UPDATE room_type SET capacity = 2 WHERE id = ?", DOUBLE_TYPE);
        jdbc.update("UPDATE room_type SET capacity = 2 WHERE id = ?", TRIPLE_TYPE);
    }

    /** Confirms a DRAFT may exceed capacity, Confirm blocks it (status unchanged), and adding a SINGLE makes it valid. */
    @Test
    void shouldAllowOverCapacityDraftButBlockConfirmUntilCapacityIsEnough() {
        UUID doubleRoom = room("D", DOUBLE_TYPE);
        UUID singleRoom = room("S", SINGLE_TYPE);
        LocalDate in = LocalDate.now().plusDays(200);
        Response draft = service.create(request(in, 3, 1, doubleRoom));

        ResponseStatusException blocked = assertThrows(ResponseStatusException.class, () -> service.confirm(draft.id()));
        assertTrue(blocked.getReason().contains("3 adults") && blocked.getReason().contains("only 2"));
        assertEquals("DRAFT", status(draft.id()));

        service.updateDraft(draft.id(), request(in, 3, 1, doubleRoom, singleRoom));
        assertEquals("CONFIRMED", service.confirm(draft.id()).status());
    }

    /** Confirms a NULL capacity blocks Confirm (never zero, unlimited or skipped) and leaves the draft untouched. */
    @Test
    void shouldBlockConfirmForNullCapacity() {
        UUID room = room("N", DOUBLE_TYPE);
        Response draft = service.create(request(LocalDate.now().plusDays(210), 1, 0, room));
        jdbc.update("UPDATE room_type SET capacity = NULL WHERE id = ?", DOUBLE_TYPE);

        ResponseStatusException blocked = assertThrows(ResponseStatusException.class, () -> service.confirm(draft.id()));

        assertTrue(blocked.getReason().contains("Room capacity is not configured for room type"));
        assertEquals("DRAFT", status(draft.id()));
    }

    /** Confirms check-in re-checks the CURRENT capacity: lowering it after confirmation blocks and creates nothing. */
    @Test
    void shouldBlockCheckInWhenCapacityDropsAfterConfirmation() {
        UUID room = room("C", DOUBLE_TYPE);
        LocalDate in = LocalDate.now(clock);
        Response draft = service.create(request(in, 2, 0, room));
        service.confirm(draft.id());
        jdbc.update("UPDATE room_type SET capacity = 1 WHERE id = ?", DOUBLE_TYPE);

        assertThrows(ResponseStatusException.class, () -> service.checkIn(draft.id()));

        assertEquals("CONFIRMED", status(draft.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", Integer.class, draft.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM charge c JOIN stay s ON s.id = c.stay_id WHERE s.reservation_id = ?",
                Integer.class, draft.id()));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room));

        jdbc.update("UPDATE room_type SET capacity = 2 WHERE id = ?", DOUBLE_TYPE);
        assertEquals("CHECKED_IN", service.checkIn(draft.id()).status());
    }

    /** Confirms reassignment validates the resulting room set atomically: a reduction fails unchanged, a like-for-like passes. */
    @Test
    void shouldValidateResultingCapacityOnReassignment() {
        UUID doubleRoom = room("RD", DOUBLE_TYPE);
        UUID singleRoom = room("RS", SINGLE_TYPE);
        UUID otherSingle = room("RS2", SINGLE_TYPE);
        UUID otherDouble = room("RD2", DOUBLE_TYPE);
        LocalDate in = LocalDate.now().plusDays(220);
        Response confirmed = service.create(request(in, 3, 0, doubleRoom, singleRoom));
        service.confirm(confirmed.id());

        RoomReassignmentException reduced = assertThrows(RoomReassignmentException.class,
                () -> reassignment.reassign(confirmed.id(), doubleRoom, otherSingle));
        assertEquals(RoomReassignmentException.Reason.INSUFFICIENT_ADULT_CAPACITY, reduced.getReason());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id = ? AND room_id IN (?, ?)",
                Integer.class, confirmed.id(), doubleRoom, singleRoom));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE room_id = ?", Integer.class, otherSingle));

        reassignment.reassign(confirmed.id(), doubleRoom, otherDouble);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reservation_room WHERE reservation_id = ? AND room_id = ?",
                Integer.class, confirmed.id(), otherDouble));

        jdbc.update("UPDATE room_type SET capacity = NULL WHERE id = ?", TRIPLE_TYPE);
        UUID unconfigured = room("RT", TRIPLE_TYPE);
        RoomReassignmentException notConfigured = assertThrows(RoomReassignmentException.class,
                () -> reassignment.reassign(confirmed.id(), otherDouble, unconfigured));
        assertEquals(RoomReassignmentException.Reason.CAPACITY_NOT_CONFIGURED, notConfigured.getReason());
    }

    /** Confirms evaluating capacity for a multi-room set loads the RoomTypes in one batch, not per room. */
    @Test
    void shouldLoadRoomTypesInOneBatchWhenEvaluatingCapacity() {
        List<UUID> ids = List.of(room("B1", SINGLE_TYPE), room("B2", DOUBLE_TYPE), room("B3", TRIPLE_TYPE), room("B4", DOUBLE_TYPE));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long statements = transactions.execute(status -> {
            var rooms = roomRepository.lockAllByIdIn(ids.stream().sorted().toList());
            statistics.clear();
            AdultCapacityRules.Result result = AdultCapacityRules.evaluate(6, rooms);
            assertEquals(AdultCapacityRules.Outcome.VALID, result.outcome());
            assertEquals(7, result.totalAdultCapacity());
            return statistics.getPrepareStatementCount();
        });

        assertTrue(statements <= 1, "RoomTypes must load in at most one batch, not per room: " + statements);
    }

    private CreateRequest request(LocalDate in, int adults, int children, UUID... roomIds) {
        return new CreateRequest(guest, in, in.plusDays(2), adults, children, BookingSource.DIRECT, null, "VND", null,
                java.util.Arrays.stream(roomIds).map(id -> new RoomRequest(id, new BigDecimal("1000000"))).toList(), List.of());
    }

    private String status(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    private UUID room(String suffix, UUID typeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", id, "AC" + id.toString().substring(0, 5) + suffix,
                typeId, user, user);
        return id;
    }
}
