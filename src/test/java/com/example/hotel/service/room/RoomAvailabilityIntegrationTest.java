package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies booking availability against PostgreSQL with the real overlap query: a room that is OCCUPIED, DIRTY or
 * CLEANING today stays bookable for a non-overlapping future period, back-to-back stays follow the half-open date
 * semantics, and only CONFIRMED / CHECKED_IN reservations block a room.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RoomAvailabilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoomAvailabilityService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;
    private UUID guest;
    private UUID type;
    private int sequence;

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

    /** Clears reservations and rooms and creates the shared audit user, guest and RoomType. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM room_inventory_period");
        jdbc.update("DELETE FROM room");
        sequence = 0;
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "avail-" + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        type = jdbc.queryForObject("SELECT id FROM room_type LIMIT 1", UUID.class);
    }

    /** Confirms a room OCCUPIED today by a 21-23 Sep stay is bookable for 10-12 Oct, and DIRTY/CLEANING likewise. */
    @Test
    void shouldOfferRoomsThatAreBusyTodayForANonOverlappingFutureStay() {
        UUID occupied = room("101", "OCCUPIED");
        room("102", "DIRTY");
        room("103", "CLEANING");
        room("104", "MAINTENANCE");
        room("105", "OUT_OF_ORDER");
        reserve(occupied, "CHECKED_IN", "2026-09-21", "2026-09-23");

        assertEquals(List.of("101", "102", "103"), numbers(service.bookableRoomsForPeriod(date("2026-10-10"), date("2026-10-12"))));
    }

    /** Confirms a conflicting CONFIRMED reservation excludes the room and back-to-back periods are not conflicts. */
    @Test
    void shouldFollowHalfOpenDateSemanticsForBackToBackStays() {
        UUID room = room("101", "AVAILABLE");
        reserve(room, "CONFIRMED", "2026-10-10", "2026-10-12");

        assertEquals(List.of(), numbers(service.bookableRoomsForPeriod(date("2026-10-10"), date("2026-10-12"))));
        assertEquals(List.of(), numbers(service.bookableRoomsForPeriod(date("2026-10-11"), date("2026-10-13"))));
        assertEquals(List.of(), numbers(service.bookableRoomsForPeriod(date("2026-10-09"), date("2026-10-11"))));
        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(date("2026-10-12"), date("2026-10-14"))),
                "a stay starting on the other stay's check-out date is not a conflict");
        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(date("2026-10-08"), date("2026-10-10"))),
                "a stay ending on the other stay's check-in date is not a conflict");
    }

    /** Confirms only CONFIRMED and CHECKED_IN block; DRAFT, CANCELLED, NO_SHOW and CHECKED_OUT do not. */
    @Test
    void shouldBlockOnlyConfirmedAndCheckedInReservations() {
        UUID room = room("101", "AVAILABLE");
        for (String status : List.of("DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_OUT")) {
            reserve(room, status, "2026-10-10", "2026-10-12");
        }
        assertEquals(List.of("101"), numbers(service.bookableRoomsForPeriod(date("2026-10-10"), date("2026-10-12"))));

        UUID checkedIn = reserve(room, "CHECKED_IN", "2026-10-10", "2026-10-12");
        openStayAssignment(checkedIn, room);
        assertEquals(List.of(), numbers(service.bookableRoomsForPeriod(date("2026-10-10"), date("2026-10-12"))));
    }

    /** Confirms immediate check-in readiness offers only AVAILABLE rooms without a conflict. */
    @Test
    void shouldOfferOnlyAvailableRoomsForImmediateCheckIn() {
        room("101", "AVAILABLE");
        room("102", "DIRTY");
        room("103", "CLEANING");
        room("104", "MAINTENANCE");
        room("105", "OUT_OF_ORDER");
        room("106", "OCCUPIED");
        UUID booked = room("107", "AVAILABLE");
        reserve(booked, "CONFIRMED", "2026-09-21", "2026-09-23");

        assertEquals(List.of("101"), numbers(service.checkInReadyRoomsForPeriod(date("2026-09-21"), date("2026-09-22"))));
    }

    /** Confirms explicit self-exclusion ignores only the selected Reservation, never another confirmed booking. */
    @Test
    void shouldExcludeOnlyTheReservationBeingModified() {
        UUID room = room("108", "AVAILABLE");
        UUID ownReservation = reserve(room, "CONFIRMED", "2026-10-10", "2026-10-12");

        assertEquals(Set.of(room), service.conflictedRoomIds(
                List.of(room), date("2026-10-10"), date("2026-10-13")));
        assertEquals(Set.of(), service.conflictedRoomIdsExcludingReservation(
                List.of(room), date("2026-10-10"), date("2026-10-13"), ownReservation));

        reserve(room, "CONFIRMED", "2026-10-12", "2026-10-14");
        assertEquals(Set.of(room), service.conflictedRoomIdsExcludingReservation(
                List.of(room), date("2026-10-10"), date("2026-10-13"), ownReservation));
    }

    /** Confirms Reservation self-exclusion never suppresses an active StayRoomAssignment conflict. */
    @Test
    void shouldRetainActiveStayAssignmentConflictsWhenExcludingAReservation() {
        UUID room = room("109", "AVAILABLE");
        UUID ownReservation = reserve(room, "CONFIRMED", "2026-10-10", "2026-10-12");
        UUID occupiedReservation = reserve(room, "CHECKED_IN", "2026-10-10", "2026-10-14");
        openStayAssignment(occupiedReservation, room);

        assertEquals(Set.of(room), service.conflictedRoomIdsExcludingReservation(
                List.of(room), date("2026-10-10"), date("2026-10-13"), ownReservation));
    }

    private List<String> numbers(List<RoomLookupResponse> rooms) {
        return rooms.stream().map(RoomLookupResponse::roomNumber).toList();
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    private UUID room(String number, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, TRUE, now(), ?, now(), ?)", id, number, type, status, user, user);
        return id;
    }

    private UUID reserve(UUID room, String status, String checkIn, String checkOut) {
        UUID reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, check_in_date, "
                        + "check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                reservation, "AV-%04d".formatted(++sequence), guest, status, date(checkIn), date(checkOut), user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1, 1, now(), ?, now(), ?)",
                UUID.randomUUID(), reservation, room, date(checkIn), date(checkOut), user, user);
        return reservation;
    }

    /** Creates the active Stay and open assignment that authoritatively block a CHECKED_IN Reservation's Room. */
    private void openStayAssignment(UUID reservation, UUID room) {
        UUID line = jdbc.queryForObject(
                "SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        UUID stay = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', now(), now(), ?, now(), ?)", stay, reservation, user, user);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                        + "assigned_to, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, now(), NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, line, user, user);
    }
}
