package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.service.booking.ReservationQueryService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies, against PostgreSQL, that the Check-out current-room filter and the sort are applied by the
 * database BEFORE pagination: a stay on a later unfiltered page is still found, totals are exact, and
 * Room Change and multi-room stays match by their CURRENT open room assignments.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationCurrentRoomSearchIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationQueryService reservationQueryService;

    @Autowired
    private JdbcTemplate jdbc;

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

    /**
     * Builds 12 CHECKED_IN stays (more than one page of 10). Reservation 12 is the oldest by check-in date, so
     * it sits on page 2 of the unfiltered result: it originally booked room B12 but moved to room 999.
     * Reservation 3 occupies two current rooms, 301 and 302.
     */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "curroom." + user);
        UUID guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        for (int index = 1; index <= 12; index++) {
            UUID reservation = UUID.randomUUID();
            LocalDate checkIn = LocalDate.of(2026, 9, 30).minusDays(index);
            jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                            + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                            + "VALUES (?, ?, ?, 'DIRECT', 'CHECKED_IN', now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                    reservation, "R2026-%03d".formatted(index), guest, checkIn, checkIn.plusDays(2), user, user);
            UUID originalRoom = room("B%02d".formatted(index), user);
            UUID reservationRoom = reservationRoom(reservation, originalRoom, checkIn, user);
            UUID stay = UUID.randomUUID();
            jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                    + "VALUES (?, ?, 'CHECKED_IN', now(), now(), ?, now(), ?)", stay, reservation, user, user);
            if (index == 12) {
                assignment(stay, originalRoom, reservationRoom, "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", user);
                assignment(stay, room("999", user), reservationRoom, "2026-09-02T00:00:00Z", null, user);
            } else if (index == 3) {
                assignment(stay, room("301", user), reservationRoom, "2026-09-01T00:00:00Z", null, user);
                UUID secondRoomReservation = reservationRoom(reservation, room("302", user), checkIn, user);
                assignment(stay, roomId("302"), secondRoomReservation, "2026-09-01T00:00:00Z", null, user);
            } else {
                assignment(stay, originalRoom, reservationRoom, "2026-09-01T00:00:00Z", null, user);
            }
        }
    }

    /** Confirms a matching current room on what would be the second unfiltered page is found, with an exact total. */
    @Test
    void currentRoomOnLaterUnfilteredPageIsFoundWithCorrectTotal() {
        Page<ReservationSummaryResponse> unfilteredFirst = reservationQueryService.findPage(criteria(null), 0);
        assertEquals(12, unfilteredFirst.getTotalElements());
        assertFalse(unfilteredFirst.getContent().stream().anyMatch(r -> r.reservationNumber().equals("R2026-012")),
                "the target must not be on the first unfiltered page");

        Page<ReservationSummaryResponse> filtered = reservationQueryService.findPage(criteria("999"), 0);

        assertEquals(1, filtered.getTotalElements());
        assertEquals(1, filtered.getTotalPages());
        assertEquals("R2026-012", filtered.getContent().get(0).reservationNumber());
    }

    /** Confirms a Room Change releases the original room: the old room no longer matches, the new one does. */
    @Test
    void roomChangeMatchesCurrentRoomNotOriginalBookingRoom() {
        assertEquals(0, reservationQueryService.findPage(criteria("B12"), 0).getTotalElements());
        assertEquals(1, reservationQueryService.findPage(criteria("999"), 0).getTotalElements());
    }

    /** Confirms a multi-room stay matches on any current room and is counted once. */
    @Test
    void multiRoomStayMatchesAnyCurrentRoomOnce() {
        assertEquals(1, reservationQueryService.findPage(criteria("302"), 0).getTotalElements());
        Page<ReservationSummaryResponse> both = reservationQueryService.findPage(criteria("30"), 0);
        assertEquals(1, both.getTotalElements());
        assertEquals("R2026-003", both.getContent().get(0).reservationNumber());
    }

    /** Confirms sort is applied by the database before pagination: descending number puts 012 first on page 1. */
    @Test
    void sortIsAppliedBeforePagination() {
        ReservationSearchCriteria criteria = criteria(null);
        criteria.setSort("reservationNumber");
        criteria.setDir("desc");

        List<String> firstPage = reservationQueryService.findPage(criteria, 0).getContent().stream()
                .map(ReservationSummaryResponse::reservationNumber).toList();
        List<String> secondPage = reservationQueryService.findPage(criteria, 1).getContent().stream()
                .map(ReservationSummaryResponse::reservationNumber).toList();

        assertEquals("R2026-012", firstPage.get(0));
        assertEquals(10, firstPage.size());
        assertEquals(List.of("R2026-002", "R2026-001"), secondPage);
        assertTrue(firstPage.indexOf("R2026-012") < firstPage.indexOf("R2026-003"));
    }

    private ReservationSearchCriteria criteria(String currentRoom) {
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        criteria.setStatus(ReservationStatus.CHECKED_IN);
        criteria.setCurrentRoom(currentRoom);
        return criteria;
    }

    private UUID room(String number, UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO NOTHING",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return roomId(number);
    }

    private UUID roomId(String number) {
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID reservationRoom(UUID reservation, UUID room, LocalDate checkIn, UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1, 1, now(), ?, now(), ?)",
                id, reservation, room, checkIn, checkIn.plusDays(2), user, user);
        return id;
    }

    private void assignment(UUID stay, UUID room, UUID reservationRoom, String from, String to, UUID user) {
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, reservationRoom, from, to, user, user);
    }
}
