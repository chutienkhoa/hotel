package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.dto.room.response.HousekeepingWorkspaceResponse;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.room.HousekeepingQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

/** Verifies, against PostgreSQL, which Reservation statuses and dates qualify as a Room's next arrival. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HousekeepingNextArrivalIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private HousekeepingQueryService queryService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

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

    /** Clears reservations and creates a user and guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "hk." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "staff"), null, List.of()));
    }

    /** Confirms only CONFIRMED, not-yet-past arrivals count, and the nearest one wins per Room. */
    @Test
    void nextArrivalUsesOnlyConfirmedUpcomingReservationsAndTheNearest() {
        LocalDate today = LocalDate.now(clock);
        UUID room = room("HK-NEAR");
        reservation(room, "CONFIRMED", today.plusDays(5));
        reservation(room, "CONFIRMED", today.plusDays(2));
        reservation(room, "CANCELLED", today);
        reservation(room, "NO_SHOW", today.plusDays(1));
        reservation(room, "CHECKED_OUT", today);
        reservation(room, "CHECKED_IN", today);
        reservation(room, "DRAFT", today);
        reservation(room, "CONFIRMED", today.minusDays(1));
        UUID onlyIneligible = room("HK-NONE");
        reservation(onlyIneligible, "CANCELLED", today);
        reservation(onlyIneligible, "CHECKED_OUT", today.minusDays(3));

        Map<UUID, LocalDate> arrivals = reservationRepository
                .findNextArrivalsFrom(today, HousekeepingQueryService.UPCOMING_ARRIVAL_STATUSES).stream()
                .collect(Collectors.toMap(RoomNextArrivalRow::roomId, RoomNextArrivalRow::nextArrivalDate));

        assertEquals(today.plusDays(2), arrivals.get(room));
        assertEquals(null, arrivals.get(onlyIneligible));
    }

    /** Confirms the workspace read model runs against the real schema and lists a DIRTY room as urgent. */
    @Test
    void workspaceMarksDirtyRoomWithArrivalTodayAsUrgent() {
        UUID room = room("HK-URGENT");
        jdbc.update("UPDATE room SET status = 'DIRTY' WHERE id = ?", room);
        reservation(room, "CONFIRMED", LocalDate.now(clock));

        HousekeepingWorkspaceResponse workspace = queryService.loadWorkspace();

        assertEquals(true, workspace.needsCleaning().stream()
                .anyMatch(row -> row.roomNumber().equals("HK-URGENT") && row.urgent()));
    }

    /** Confirms an inactive DIRTY or AVAILABLE Room never appears in any Housekeeping group, ready included. */
    @Test
    void workspaceExcludesInactiveRooms() {
        UUID inactiveAvailable = room("HK-INACTIVE-A");
        UUID inactiveDirty = room("HK-INACTIVE-D");
        jdbc.update("UPDATE room SET active = FALSE WHERE id = ?", inactiveAvailable);
        jdbc.update("UPDATE room SET active = FALSE, status = 'DIRTY' WHERE id = ?", inactiveDirty);

        HousekeepingWorkspaceResponse workspace = queryService.loadWorkspace();

        assertEquals(false, java.util.stream.Stream.of(workspace.ready(), workspace.needsCleaning(),
                        workspace.cleaning(), workspace.issues())
                .flatMap(java.util.List::stream)
                .anyMatch(row -> row.roomNumber().startsWith("HK-INACTIVE")));
    }

    /** Confirms Housekeeping next-arrival derives its date from the synchronized ReservationRoom snapshot. */
    @Test
    void nextArrivalObservesChangedReservationRoomDates() {
        LocalDate today = LocalDate.now(clock);
        UUID room = room("HK-MOVED");
        UUID reservation = reservation(room, "CONFIRMED", today.plusDays(5));

        reservationService.changeConfirmedDates(
                reservation, new ReservationDateChangeRequest(today.plusDays(2), today.plusDays(4)));

        Map<UUID, LocalDate> arrivals = reservationRepository
                .findNextArrivalsFrom(today, HousekeepingQueryService.UPCOMING_ARRIVAL_STATUSES).stream()
                .collect(Collectors.toMap(RoomNextArrivalRow::roomId, RoomNextArrivalRow::nextArrivalDate));
        assertEquals(today.plusDays(2), arrivals.get(room));
    }

    private UUID room(String number) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO NOTHING",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID reservation(UUID room, String status, LocalDate checkIn) {
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                reservation, "H" + reservation.toString().substring(0, 12), guest, status, checkIn, checkIn.plusDays(2), user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1, 1, now(), ?, now(), ?)",
                UUID.randomUUID(), reservation, room, checkIn, checkIn.plusDays(2), user, user);
        return reservation;
    }
}
