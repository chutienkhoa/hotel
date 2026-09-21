package com.example.hotel.repository.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.customer.GuestQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * Verifies against PostgreSQL that Guest is a reusable profile for Reservation selection: a Guest with a historical
 * CHECKED_OUT Reservation remains selectable as Primary and as Accompanying Guest, and no profile is duplicated.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GuestSelectionEligibilityIntegrationTest {

    private static final UUID DOUBLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private GuestQueryService guestQueryService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
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

    /** Creates the acting user and a room. */
    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "ge." + user);
        room = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)", room, "GE" + room.toString().substring(0, 6),
                DOUBLE_ROOM_TYPE_ID, user, user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Confirms a never-booked Guest and a Guest with a CHECKED_OUT Reservation are both offered by both pickers. */
    @Test
    void shouldOfferGuestsRegardlessOfHistoricalReservations() {
        UUID neverBooked = guest();
        UUID returning = guest();
        historicalCheckedOutReservation(returning);

        List<UUID> creation = ids(guestQueryService.findAllForReservationCreation());
        List<UUID> editing = ids(guestQueryService.findAllForReservationEditing(neverBooked));

        assertTrue(creation.containsAll(List.of(neverBooked, returning)));
        assertTrue(editing.containsAll(List.of(neverBooked, returning)));
    }

    /** Confirms the returning Guest can be Primary on a new Reservation and Accompanying on another, with no new profile. */
    @Test
    void shouldAllowReturningGuestAsPrimaryAndAsAccompanyingWithoutDuplicatingProfiles() {
        UUID returning = guest();
        UUID other = guest();
        historicalCheckedOutReservation(returning);
        int guestsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM guest", Integer.class);
        LocalDate in = LocalDate.now().plusDays(120);

        reservationService.create(new CreateRequest(returning, in, in.plusDays(2), 2, 0, BookingSource.DIRECT, null, "VND",
                null, List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of()));
        var asCompanion = reservationService.create(new CreateRequest(other, in.plusDays(10), in.plusDays(12), 2, 0,
                BookingSource.DIRECT, null, "VND", null, List.of(new RoomRequest(room, new BigDecimal("1000000"))),
                List.of(returning)));

        assertEquals(guestsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM guest", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_guest WHERE reservation_id = ? AND guest_id = ?", Integer.class,
                asCompanion.id(), returning));
        assertEquals(returning, jdbc.queryForObject(
                "SELECT guest_id FROM reservation WHERE check_in_date = ?", UUID.class, in));
    }

    private List<UUID> ids(List<GuestLookupResponse> guests) {
        return guests.stream().map(GuestLookupResponse::id).toList();
    }

    private UUID guest() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", id, "G" + id.toString().substring(0, 10), user, user);
        return id;
    }

    private void historicalCheckedOutReservation(UUID guest) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, "
                        + "check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CHECKED_OUT', now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, "GE" + id.toString().substring(0, 12), guest, LocalDate.now().minusDays(20),
                LocalDate.now().minusDays(18), user, user);
    }
}
