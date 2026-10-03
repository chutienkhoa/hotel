package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.FrontDeskSearchCriteria;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayExtensionService;
import java.math.BigDecimal;
import java.time.Clock;
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
 * Verifies the Front Desk read model against PostgreSQL: arrival inclusion, the check-in to in-house hand-over,
 * OPEN-only current rooms after a Room Change, and the grouped balance queries feeding departure readiness.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FrontDeskReadModelIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private FrontDeskQueryService frontDesk;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StayExtensionService stayExtension;

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
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "fd." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "staff"), null, List.of()));
    }

    /** Confirms only today's and overdue CONFIRMED reservations are arrivals; other statuses and future are not. */
    @Test
    void shouldIncludeOnlyTodayAndOverdueConfirmedArrivals() {
        add("R-TODAY", "CONFIRMED", today, today.plusDays(1), "FA-1");
        add("R-OVERDUE", "CONFIRMED", today.minusDays(2), today.plusDays(1), "FA-2");
        add("R-FUTURE", "CONFIRMED", today.plusDays(1), today.plusDays(3), "FA-3");
        int index = 4;
        for (String status : List.of("DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT")) {
            add("R-" + status, status, today, today.plusDays(1), "FA-" + index++);
        }

        List<String> numbers = frontDesk.arrivals().stream().map(FrontDeskArrivalRow::reservationNumber).toList();

        assertEquals(List.of("R-OVERDUE", "R-TODAY"), numbers);
        assertEquals(2, numbers.size());
        assertTrue(numbers.containsAll(List.of("R-TODAY", "R-OVERDUE")));
    }

    /** Confirms Front Desk immediately observes a confirmed Reservation moved away from today's arrivals. */
    @Test
    void shouldObserveChangedReservationDates() {
        UUID reservation = add("R-RESCHEDULED", "CONFIRMED", today, today.plusDays(2), "FA-MOVED");
        assertEquals(List.of("R-RESCHEDULED"),
                frontDesk.arrivals().stream().map(FrontDeskArrivalRow::reservationNumber).toList());

        reservationService.changeConfirmedDates(
                reservation, new ReservationDateChangeRequest(today.plusDays(3), today.plusDays(5)));

        assertTrue(frontDesk.arrivals().isEmpty());
    }

    /**
     * Confirms Front Desk Arrivals resolves the effective Booking Contact against real PostgreSQL data: the
     * Primary Guest fallback for a historical/null Booking Contact, and the Reservation's own snapshot when set.
     */
    @Test
    void shouldResolveEffectiveContactPhoneFromSnapshotOrPrimaryGuestFallback() {
        jdbc.update("UPDATE guest SET phone = ? WHERE id = ?", "0900000001", guest);
        UUID withoutSnapshot = add("R-FALLBACK", "CONFIRMED", today, today.plusDays(1), "FA-CONTACT-1");
        UUID withSnapshot = add("R-SNAPSHOT", "CONFIRMED", today, today.plusDays(1), "FA-CONTACT-2");
        jdbc.update("UPDATE reservation SET booking_contact_phone = ? WHERE id = ?", "0955555555", withSnapshot);

        List<FrontDeskArrivalRow> rows = frontDesk.arrivals();
        String fallbackPhone = rows.stream()
                .filter(row -> row.reservationId().equals(withoutSnapshot)).findFirst().orElseThrow().contactPhone();
        String snapshotPhone = rows.stream()
                .filter(row -> row.reservationId().equals(withSnapshot)).findFirst().orElseThrow().contactPhone();

        assertEquals("0900000001", fallbackPhone);
        assertEquals("0955555555", snapshotPhone);
    }

    /** Confirms check-in moves a reservation from Arrivals to In-house and Departures with grouped balance readiness. */
    @Test
    void shouldHandOverFromArrivalsToInHouseAndDeriveBalanceReadiness() {
        UUID reservation = add("R-CHECKIN", "CONFIRMED", today.minusDays(1), today, "FB-1");
        assertEquals(1, frontDesk.arrivals().size());

        reservationService.checkIn(reservation);

        assertTrue(frontDesk.arrivals().isEmpty());
        List<FrontDeskStayRow> inHouse = frontDesk.inHouse();
        assertEquals(1, inHouse.size());
        assertEquals(List.of("FB-1"), inHouse.get(0).rooms().stream().map(room -> room.roomNumber()).toList());
        List<FrontDeskStayRow> owing = frontDesk.departures(true);
        assertEquals(1, owing.size());
        assertTrue(owing.get(0).paymentRequired());
        assertTrue(owing.get(0).needsAttention());
        assertEquals(0, new BigDecimal("1000000").compareTo(owing.get(0).outstanding()));
        assertNull(frontDesk.departures(false).get(0).outstanding());

        UUID stay = jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("1000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));

        FrontDeskStayRow paid = frontDesk.departures(true).get(0);
        assertFalse(paid.paymentRequired());
        assertEquals(0, BigDecimal.ZERO.compareTo(paid.outstanding()));
        assertFalse(paid.needsAttention());
    }

    /** Confirms a future departure is excluded from Departures but present in In-house. */
    @Test
    void shouldExcludeFutureDeparturesButKeepThemInHouse() {
        UUID reservation = add("R-STAY", "CONFIRMED", today, today.plusDays(3), "FC-1");
        reservationService.checkIn(reservation);

        assertTrue(frontDesk.departures(true).isEmpty());
        assertEquals(1, frontDesk.inHouse().size());
    }

    /** Confirms only OPEN assignments are current rooms after a Room Change; the closed one is never shown. */
    @Test
    void shouldShowOnlyOpenAssignmentsAsCurrentRooms() {
        UUID reservation = add("R-MOVED", "CONFIRMED", today, today.plusDays(2), "FD-OLD");
        reservationService.checkIn(reservation);
        UUID newRoom = room("FD-NEW", "OCCUPIED");
        UUID stay = jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
        UUID lineId = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("UPDATE stay_room_assignment SET assigned_to = now() WHERE stay_id = ?", stay);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, now(), NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, newRoom, lineId, user, user);

        FrontDeskStayRow row = frontDesk.inHouse().get(0);

        assertEquals(List.of("FD-NEW"), row.rooms().stream().map(room -> room.roomNumber()).toList());
    }

    /** Confirms a Stay row carries the Reservation's booking source and its full stay length, not the elapsed nights. */
    @Test
    void shouldExposeBookingSourceAndFullStayLengthOnStayRows() {
        UUID reservation = add("R-NIGHTS", "CONFIRMED", today.minusDays(1), today.plusDays(2), "FN-1");
        reservationService.checkIn(reservation);

        FrontDeskStayRow row = frontDesk.inHouse().get(0);

        assertEquals(BookingSource.DIRECT, row.source());
        assertEquals(3L, row.nights());
    }

    /**
     * Confirms the Batch 3A paged Arrivals method ({@link FrontDeskQueryService#arrivals(FrontDeskSearchCriteria, int)})
     * selects exactly the same business population, in the same order, as the original {@link FrontDeskQueryService#arrivals()}
     * against real PostgreSQL data, when no search/sort is requested.
     */
    @Test
    void shouldPreserveArrivalsSelectionThroughThePagedMethod() {
        add("R-TODAY", "CONFIRMED", today, today.plusDays(1), "FP-1");
        add("R-OVERDUE", "CONFIRMED", today.minusDays(2), today.plusDays(1), "FP-2");

        List<String> original = frontDesk.arrivals().stream().map(FrontDeskArrivalRow::reservationNumber).toList();
        List<String> paged = frontDesk.arrivals(new FrontDeskSearchCriteria(), 0).getContent().stream()
                .map(FrontDeskArrivalRow::reservationNumber).toList();

        assertEquals(original, paged);
    }

    /** Confirms a search fragment only narrows Arrivals; it never surfaces a Reservation outside the existing business scope. */
    @Test
    void shouldNarrowArrivalsBySearchWithoutBroadeningSelection() {
        add("R-ALPHA", "CONFIRMED", today, today.plusDays(1), "FQ-1");
        add("R-BETA", "CONFIRMED", today, today.plusDays(1), "FQ-2");
        FrontDeskSearchCriteria search = new FrontDeskSearchCriteria();
        search.setSearch("ALPHA");

        List<String> filtered = frontDesk.arrivals(search, 0).getContent().stream()
                .map(FrontDeskArrivalRow::reservationNumber).toList();

        assertEquals(List.of("R-ALPHA"), filtered);
    }

    /** Confirms In-house shows the NEW planned checkout after a Stay Extension, never the original one. */
    @Test
    void shouldShowExtendedPlannedCheckoutForInHouseStay() {
        UUID reservation = add("R-EXTENDED", "CONFIRMED", today, today.plusDays(2), "FX-EXT");
        reservationService.checkIn(reservation);

        stayExtension.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4)));

        FrontDeskStayRow row = frontDesk.inHouse().get(0);
        assertEquals(today.plusDays(4), row.plannedCheckOutDate());
        assertEquals(4L, row.nights());
        assertFalse(row.overdue());
    }

    /** Confirms a checked-in stay whose planned checkout is before the hotel date is flagged overdue, with its days. */
    @Test
    void shouldFlagCheckedInStayOverdueWhenPlannedCheckoutIsBeforeToday() {
        UUID reservation = add("R-LATE-STAY", "CONFIRMED", today.minusDays(3), today.minusDays(1), "FO-LATE");
        reservationService.checkIn(reservation);

        FrontDeskStayRow row = frontDesk.inHouse().get(0);
        assertTrue(row.overdue());
        assertEquals(1L, row.overdueDays());
    }

    /**
     * Confirms the In-house Room Type filter follows the room the guest occupies after a Room Change: the booked
     * Single room no longer matches, the open Double assignment does.
     */
    @Test
    void shouldFilterInHouseRoomTypeByCurrentRoomAfterRoomChange() {
        UUID reservation = add("R-TYPE-MOVED", "CONFIRMED", today, today.plusDays(2), "FT-OLD");
        reservationService.checkIn(reservation);
        UUID newRoom = roomOfType("FT-NEW", DOUBLE_ROOM_TYPE_ID);
        UUID stay = jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
        UUID lineId = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("UPDATE stay_room_assignment SET assigned_to = now() WHERE stay_id = ?", stay);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, now(), NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, newRoom, lineId, user, user);

        assertEquals(List.of("R-TYPE-MOVED"), inHouseByRoomType(DOUBLE_ROOM_TYPE_ID));
        assertEquals(List.of(), inHouseByRoomType(SINGLE_ROOM_TYPE_ID));
    }

    /**
     * Confirms a Departures checkout-date filter uses the planned checkout after a Stay Extension: a stay that was
     * overdue and is extended to today leaves OVERDUE and joins TODAY.
     */
    @Test
    void shouldFilterDeparturesByExtendedCheckoutDate() {
        UUID reservation = add("R-EXT-DEP", "CONFIRMED", today.minusDays(2), today.minusDays(1), "FE-DEP");
        reservationService.checkIn(reservation);
        FrontDeskSearchCriteria overdue = new FrontDeskSearchCriteria();
        overdue.setCheckoutDate("OVERDUE");
        FrontDeskSearchCriteria dueToday = new FrontDeskSearchCriteria();
        dueToday.setCheckoutDate("TODAY");
        assertEquals(List.of("R-EXT-DEP"), departuresFiltered(overdue));

        stayExtension.extend(reservation, new StayExtensionRequest(today.minusDays(1), today));

        assertEquals(List.of(), departuresFiltered(overdue));
        assertEquals(List.of("R-EXT-DEP"), departuresFiltered(dueToday));
        assertEquals(today, frontDesk.inHouse().get(0).plannedCheckOutDate());
    }

    private List<String> inHouseByRoomType(UUID roomType) {
        FrontDeskSearchCriteria criteria = new FrontDeskSearchCriteria();
        criteria.setRoomType(roomType.toString());
        return frontDesk.inHouse(criteria, 0).getContent().stream().map(FrontDeskStayRow::reservationNumber).toList();
    }

    private List<String> departuresFiltered(FrontDeskSearchCriteria criteria) {
        return frontDesk.departures(true, criteria, 0).getContent().stream()
                .map(FrontDeskStayRow::reservationNumber).toList();
    }

    private UUID roomOfType(String number, UUID roomType) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'OCCUPIED', TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET room_type_id = EXCLUDED.room_type_id",
                UUID.randomUUID(), number, roomType, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID add(String number, String status, LocalDate in, LocalDate out, String roomNumber) {
        UUID room = room(roomNumber, "AVAILABLE");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', 1, now(), ?, now(), ?)",
                id, number, guest, status, in, out, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, 1000000, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out,
                new BigDecimal("1000000").multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, out))),
                user, user);
        return id;
    }

    private UUID room(String number, String status) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET status = EXCLUDED.status",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, status, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }
}
