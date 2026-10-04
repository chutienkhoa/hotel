package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ReservationListCriteria;
import com.example.hotel.dto.booking.response.ReservationListRoomResponse;
import com.example.hotel.dto.booking.response.ReservationListRowResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.service.booking.ReservationQueryService;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
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
 * Verifies the Task33 Reservation List query against PostgreSQL: the unified OR search (including the operational
 * room semantics), the half-open Stay date overlap, status/source filters, combined filters, DB-level pagination and
 * sorting, the room read model per Reservation state, and that a page needs a bounded number of SQL statements.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationListQueryIntegrationTest {

    private static final UUID SINGLE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DOUBLE = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID TWIN = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationQueryService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID user;

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

    /** Empties the reservation tables and creates the audit user shared by every row. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM guest");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "list." + user);
    }

    // ---- unified search ---------------------------------------------------------------------------------------

    /** Confirms reservation number, guest first/last/full name, guest code and OTA reference are each searchable. */
    @Test
    void searchMatchesEveryApprovedField() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID nguyen = guest("G-NVA02", "Nguyen", "Van An");
        reservation("R-ALPHA-1", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-03");
        reservation("R-BETA-2", nguyen, "CONFIRMED", "AGODA", "OTA-ABC-123", "2026-10-01", "2026-10-03");

        assertEquals(List.of("R-ALPHA-1"), numbers(search("alpha")), "reservation number, case-insensitive partial");
        assertEquals(List.of("R-ALPHA-1"), numbers(search("Ann")), "guest first name");
        assertEquals(List.of("R-ALPHA-1"), numbers(search("lee")), "guest last name");
        assertEquals(List.of("R-ALPHA-1"), numbers(search("Ann Lee")), "displayed full name");
        assertEquals(List.of("R-BETA-2"), numbers(search("nguyen van an")), "displayed full name, first + last");
        assertEquals(List.of("R-BETA-2"), numbers(search("G-NVA")), "guest code");
        assertEquals(List.of("R-BETA-2"), numbers(search("abc-12")), "OTA booking reference");
        assertEquals(List.of(), numbers(search("no-such-thing")), "no match");
        assertEquals(0, search("no-such-thing").getTotalElements());
    }

    /** Confirms surrounding whitespace is ignored and a blank search applies no filter. */
    @Test
    void searchTrimsWhitespaceAndBlankMeansNoFilter() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        reservation("R-1", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-03");
        reservation("R-2", ann, "CONFIRMED", "DIRECT", null, "2026-10-04", "2026-10-06");

        assertEquals(2, search("   Ann   ").getTotalElements());
        assertEquals(2, search("    ").getTotalElements());
    }

    /** Confirms the fields are ORed (a fragment can match different fields of different reservations). */
    @Test
    void searchCombinesFieldsWithOr() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID bob = guest("G-BOB02", "Bob", "Stone");
        reservation("R-GUEST", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-03");
        reservation("R-OTA", bob, "CONFIRMED", "AGODA", "ANN-77", "2026-10-01", "2026-10-03");
        reservation("R-OTHER", bob, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-03");

        assertEquals(List.of("R-GUEST", "R-OTA"), numbers(search("ann")));
    }

    /** Confirms the legacy externalBookingId is not treated as the OTA booking reference. */
    @Test
    void searchDoesNotMatchExternalBookingId() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID id = reservation("R-EXT", ann, "CONFIRMED", "AGODA", "OTA-REAL", "2026-10-01", "2026-10-03");
        jdbc.update("UPDATE reservation SET external_booking_id = 'EXT-999' WHERE id = ?", id);

        assertEquals(0, search("EXT-999").getTotalElements());
        assertEquals(1, search("OTA-REAL").getTotalElements());
    }

    // ---- room semantics ---------------------------------------------------------------------------------------

    /** Confirms pre-stay statuses display and search their booked rooms (all of them, with room types). */
    @Test
    void preStayStatusesUseBookedRoomsIncludingMultiRoom() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED", "NO_SHOW")) {
            UUID id = reservation("R-" + status, ann, status, "DIRECT", null, "2026-10-01", "2026-10-03");
            bookedRoom(id, "B-" + status + "-1", DOUBLE, "2026-10-01", "2026-10-03");
            bookedRoom(id, "B-" + status + "-2", TWIN, "2026-10-01", "2026-10-03");
        }

        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED", "NO_SHOW")) {
            Page<ReservationListRowResponse> found = search("B-" + status + "-2");
            assertEquals(1, found.getTotalElements(), status);
            assertEquals(
                    List.of(new ReservationListRoomResponse("B-" + status + "-1", "Double Room"),
                            new ReservationListRoomResponse("B-" + status + "-2", "Twin Room")),
                    found.getContent().get(0).rooms(), status);
        }
        assertEquals(4, search("B-").getTotalElements(), "a multi-room reservation is counted once");
    }

    /** Confirms CHECKED_IN after a Room Change shows and searches the current room, not the original one. */
    @Test
    void checkedInUsesCurrentRoomAfterRoomChange() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID id = reservation("R-IN", ann, "CHECKED_IN", "DIRECT", null, "2026-10-01", "2026-10-05");
        UUID original = bookedRoom(id, "201", DOUBLE, "2026-10-01", "2026-10-05");
        UUID stay = stay(id, "CHECKED_IN", null);
        assignment(stay, roomId("201"), original, "2026-10-01T10:00:00Z", "2026-10-02T10:00:00Z");
        assignment(stay, room("305", TWIN), original, "2026-10-02T10:00:00Z", null);

        Page<ReservationListRowResponse> byCurrent = search("305");
        assertEquals(1, byCurrent.getTotalElements());
        assertEquals(List.of(new ReservationListRoomResponse("305", "Twin Room")), byCurrent.getContent().get(0).rooms());
        assertEquals(0, search("201").getTotalElements(), "the original room is no longer the operational room");
    }

    /** Confirms a multi-room CHECKED_IN stay shows every current room and matches on any of them once. */
    @Test
    void checkedInMultiRoomShowsAllCurrentRooms() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID id = reservation("R-IN2", ann, "CHECKED_IN", "DIRECT", null, "2026-10-01", "2026-10-05");
        UUID first = bookedRoom(id, "311", DOUBLE, "2026-10-01", "2026-10-05");
        UUID second = bookedRoom(id, "312", TWIN, "2026-10-01", "2026-10-05");
        UUID stay = stay(id, "CHECKED_IN", null);
        assignment(stay, roomId("311"), first, "2026-10-01T10:00:00Z", null);
        assignment(stay, roomId("312"), second, "2026-10-01T10:00:00Z", null);

        assertEquals(1, search("31").getTotalElements());
        assertEquals(
                List.of(new ReservationListRoomResponse("311", "Double Room"), new ReservationListRoomResponse("312", "Twin Room")),
                search("312").getContent().get(0).rooms());
    }

    /** Confirms CHECKED_OUT shows and searches the final assignment(s) closed at checkout only. */
    @Test
    void checkedOutUsesFinalAssignments() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID id = reservation("R-OUT", ann, "CHECKED_OUT", "DIRECT", null, "2026-10-01", "2026-10-05");
        UUID original = bookedRoom(id, "401", DOUBLE, "2026-10-01", "2026-10-05");
        UUID stay = stay(id, "CHECKED_OUT", "2026-10-05T09:00:00Z");
        assignment(stay, roomId("401"), original, "2026-10-01T10:00:00Z", "2026-10-02T10:00:00Z");
        assignment(stay, room("405", TWIN), original, "2026-10-02T10:00:00Z", "2026-10-05T09:00:00Z");

        Page<ReservationListRowResponse> byFinal = search("405");
        assertEquals(1, byFinal.getTotalElements());
        assertEquals(List.of(new ReservationListRoomResponse("405", "Twin Room")), byFinal.getContent().get(0).rooms());
        assertEquals(0, search("401").getTotalElements(), "a room vacated by a Room Change is not a final room");
    }

    // ---- Stay date --------------------------------------------------------------------------------------------

    /** Confirms the half-open overlap predicate for [04/10, 10/10) including both exclusive boundaries. */
    @Test
    void stayDateUsesHalfOpenOverlap() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        reservation("D1-01to05", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-05");
        reservation("D2-04to10", ann, "CONFIRMED", "DIRECT", null, "2026-10-04", "2026-10-10");
        reservation("D3-08to12", ann, "CONFIRMED", "DIRECT", null, "2026-10-08", "2026-10-12");
        reservation("D4-01to20", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-20");
        reservation("X1-01to04", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-04");
        reservation("X2-10to12", ann, "CONFIRMED", "DIRECT", null, "2026-10-10", "2026-10-12");
        reservation("X3-sep", ann, "CONFIRMED", "DIRECT", null, "2026-09-20", "2026-09-23");

        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setStayFrom(LocalDate.of(2026, 10, 4));
        criteria.setStayTo(LocalDate.of(2026, 10, 10));
        criteria.setSort("reservationNumber");
        criteria.setDir("asc");

        assertEquals(List.of("D1-01to05", "D2-04to10", "D3-08to12", "D4-01to20"),
                numbers(service.findListPage(criteria, 0)));
        assertEquals(7, service.findListPage(new ReservationListCriteria(), 0).getTotalElements(), "cleared range");
    }

    /** Confirms the planned Reservation dates are used, not the dates of its ReservationRoom lines. */
    @Test
    void stayDateUsesReservationDatesNotRoomDates() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID id = reservation("R-EXTENDED", ann, "CHECKED_IN", "DIRECT", null, "2026-10-01", "2026-10-12");
        bookedRoom(id, "601", DOUBLE, "2026-10-01", "2026-10-05");

        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setStayFrom(LocalDate.of(2026, 10, 8));
        criteria.setStayTo(LocalDate.of(2026, 10, 10));

        assertEquals(1, service.findListPage(criteria, 0).getTotalElements());
    }

    // ---- status / source / combined ---------------------------------------------------------------------------

    /** Confirms every Reservation status and every booking source filters exactly. */
    @Test
    void statusAndSourceFiltersMatchExactly() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        int index = 0;
        for (ReservationStatus status : ReservationStatus.values()) {
            reservation("S-" + status, ann, status.name(), "DIRECT", null, "2026-10-0" + (++index), "2026-10-09");
        }
        for (BookingSource source : BookingSource.values()) {
            String ota = source == BookingSource.DIRECT ? null : "OTA-" + source;
            reservation("O-" + source, ann, "CONFIRMED", source.name(), ota, "2026-11-01", "2026-11-03");
        }

        for (ReservationStatus status : ReservationStatus.values()) {
            ReservationListCriteria criteria = new ReservationListCriteria();
            criteria.setStatus(status);
            Page<ReservationListRowResponse> page = service.findListPage(criteria, 0);
            assertTrue(page.getContent().stream().allMatch(row -> row.status() == status), status.name());
            assertTrue(numbers(page).contains("S-" + status), status.name());
        }
        for (BookingSource source : BookingSource.values()) {
            ReservationListCriteria criteria = new ReservationListCriteria();
            criteria.setSource(source);
            criteria.setStatus(ReservationStatus.CONFIRMED);
            Page<ReservationListRowResponse> page = service.findListPage(criteria, 0);
            assertTrue(numbers(page).contains("O-" + source), source.name());
            assertTrue(page.getContent().stream().allMatch(row -> row.source() == source), source.name());
        }
    }

    /** Confirms search, Stay date, status and source combine with AND. */
    @Test
    void combinedFiltersAreAnded() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        UUID bob = guest("G-BOB02", "Bob", "Stone");
        reservation("C-MATCH", ann, "CONFIRMED", "AGODA", "A-1", "2026-10-05", "2026-10-08");
        reservation("C-WRONG-GUEST", bob, "CONFIRMED", "AGODA", "A-2", "2026-10-05", "2026-10-08");
        reservation("C-WRONG-STATUS", ann, "DRAFT", "AGODA", "A-3", "2026-10-05", "2026-10-08");
        reservation("C-WRONG-SOURCE", ann, "CONFIRMED", "DIRECT", null, "2026-10-05", "2026-10-08");
        reservation("C-WRONG-DATES", ann, "CONFIRMED", "AGODA", "A-4", "2026-12-05", "2026-12-08");

        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setSearch("ann");
        criteria.setStayFrom(LocalDate.of(2026, 10, 1));
        criteria.setStayTo(LocalDate.of(2026, 10, 31));
        criteria.setStatus(ReservationStatus.CONFIRMED);
        criteria.setSource(BookingSource.AGODA);

        assertEquals(List.of("C-MATCH"), numbers(service.findListPage(criteria, 0)));
    }

    // ---- read model -------------------------------------------------------------------------------------------

    /** Confirms the row exposes guest code, planned nights for every state, source, OTA reference and status. */
    @Test
    void rowCarriesGuestCodeAndPlannedNights() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        for (ReservationStatus status : ReservationStatus.values()) {
            reservation("N-" + status, ann, status.name(), "AGODA", "OTA-" + status, "2026-10-01", "2026-10-06");
        }

        for (ReservationListRowResponse row : service.findListPage(new ReservationListCriteria(), 0).getContent()) {
            assertEquals(5, row.nights(), row.reservationNumber());
            assertEquals("G-ANN01", row.guestCode());
            assertEquals("Ann Lee", row.guestFullName());
            assertEquals(BookingSource.AGODA, row.source());
        }
    }

    // ---- pagination / sorting ---------------------------------------------------------------------------------

    /** Confirms DB-level pagination: exact total, stable disjoint pages, and a filter applied before paging. */
    @Test
    void paginationIsDatabaseLevelAndStable() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        for (int index = 1; index <= 12; index++) {
            UUID id = reservation("P-%02d".formatted(index), ann, "CONFIRMED", "DIRECT", null,
                    "2026-10-%02d".formatted(index), "2026-10-%02d".formatted(index + 1));
            bookedRoom(id, "7%02d".formatted(index), DOUBLE, "2026-10-%02d".formatted(index), "2026-10-%02d".formatted(index + 1));
        }
        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setSort("reservationNumber");
        criteria.setDir("asc");

        Page<ReservationListRowResponse> first = service.findListPage(criteria, 0);
        Page<ReservationListRowResponse> second = service.findListPage(criteria, 1);

        assertEquals(12, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(10, first.getContent().size());
        assertEquals(List.of("P-11", "P-12"), numbers(second));
        assertTrue(numbers(first).stream().noneMatch(numbers(second)::contains));

        criteria.setSearch("712");
        assertEquals(List.of("P-12"), numbers(service.findListPage(criteria, 0)), "filter is applied before paging");
        assertEquals(1, service.findListPage(criteria, 0).getTotalElements());
    }

    /** Confirms the default order is the latest planned check-in first with the number as the tie-break. */
    @Test
    void defaultSortIsCheckInDescending() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        reservation("Z-1", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-02");
        reservation("Z-2", ann, "CONFIRMED", "DIRECT", null, "2026-10-09", "2026-10-10");
        reservation("Z-3", ann, "CONFIRMED", "DIRECT", null, "2026-10-09", "2026-10-11");

        assertEquals(List.of("Z-2", "Z-3", "Z-1"), numbers(service.findListPage(new ReservationListCriteria(), 0)));
    }

    /** Confirms every whitelisted sort key orders ascending and descending in the database. */
    @Test
    void everyWhitelistedSortKeyOrdersBothDirections() {
        UUID zed = guest("G-1", "Zed", "Alpha");
        UUID amyZulu = guest("G-2", "Amy", "Zulu");
        UUID amyBrown = guest("G-3", "Amy", "Brown");
        UUID bob = guest("G-4", "Bob", "Adams");
        reservation("K-1", zed, "DRAFT", "DIRECT", null, "2026-10-04", "2026-10-05");
        reservation("K-2", amyZulu, "CONFIRMED", "BOOKING_COM", "B2", "2026-10-02", "2026-10-03");
        reservation("K-3", amyBrown, "NO_SHOW", "AGODA", "A1", "2026-10-03", "2026-10-04");
        reservation("K-4", bob, "CANCELLED", "AIRBNB", "C3", "2026-10-01", "2026-10-02");

        assertSort("reservationNumber", List.of("K-1", "K-2", "K-3", "K-4"));
        assertSort("guest", List.of("K-3", "K-2", "K-4", "K-1"));
        assertSort("checkInDate", List.of("K-4", "K-2", "K-3", "K-1"));
        assertSort("source", List.of("K-3", "K-4", "K-2", "K-1"));
        assertSort("otaBookingReference", List.of("K-3", "K-2", "K-4", "K-1"));
        assertSort("status", List.of("K-4", "K-2", "K-1", "K-3"));
    }

    /** Confirms unsupported sort keys (including removed and computed ones) fall back to the default order. */
    @Test
    void unsupportedSortKeysFallBackToDefaultOrder() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        reservation("Q-1", ann, "CONFIRMED", "DIRECT", null, "2026-10-01", "2026-10-02");
        reservation("Q-2", ann, "CONFIRMED", "DIRECT", null, "2026-10-09", "2026-10-10");

        for (String key : List.of("nights", "room", "guest.passwordHash", "checkOutDate", "id")) {
            ReservationListCriteria criteria = new ReservationListCriteria();
            criteria.setSort(key);
            criteria.setDir("asc");
            assertEquals(List.of("Q-2", "Q-1"), numbers(service.findListPage(criteria, 0)), key);
        }
    }

    /** Confirms a page of ten multi-room rows needs a bounded number of SQL statements (no N+1). */
    @Test
    void pageNeedsBoundedStatementCount() {
        UUID ann = guest("G-ANN01", "Ann", "Lee");
        for (int index = 1; index <= 10; index++) {
            UUID id = reservation("W-%02d".formatted(index), ann, index % 2 == 0 ? "DRAFT" : "CONFIRMED", "DIRECT", null,
                    "2026-10-%02d".formatted(index), "2026-10-%02d".formatted(index + 1));
            bookedRoom(id, "8%02d".formatted(index), DOUBLE, "2026-10-%02d".formatted(index), "2026-10-%02d".formatted(index + 1));
            bookedRoom(id, "9%02d".formatted(index), TWIN, "2026-10-%02d".formatted(index), "2026-10-%02d".formatted(index + 1));
        }
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().setStatisticsEnabled(true);
        sessionFactory.getStatistics().clear();

        Page<ReservationListRowResponse> page = service.findListPage(new ReservationListCriteria(), 0);

        assertEquals(10, page.getContent().size());
        assertTrue(page.getContent().stream().allMatch(row -> row.rooms().size() == 2));
        assertTrue(sessionFactory.getStatistics().getPrepareStatementCount() <= 4,
                "page + count + booked rooms; was " + sessionFactory.getStatistics().getPrepareStatementCount());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void assertSort(String key, List<String> ascending) {
        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setSort(key);
        criteria.setDir("asc");
        assertEquals(ascending, numbers(service.findListPage(criteria, 0)), key + " asc");
        criteria.setDir("desc");
        assertEquals(ascending.reversed(), numbers(service.findListPage(criteria, 0)), key + " desc");
    }

    private Page<ReservationListRowResponse> search(String fragment) {
        ReservationListCriteria criteria = new ReservationListCriteria();
        criteria.setSearch(fragment);
        criteria.normalize();
        criteria.setSort("reservationNumber");
        criteria.setDir("asc");
        return service.findListPage(criteria, 0);
    }

    private List<String> numbers(Page<ReservationListRowResponse> page) {
        return page.getContent().stream().map(ReservationListRowResponse::reservationNumber).toList();
    }

    private UUID guest(String code, String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, now(), ?, now(), ?)", id, code, firstName, lastName, user, user);
        return id;
    }

    private UUID reservation(String number, UUID guest, String status, String source, String ota, String checkIn,
            String checkOut) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, ota_booking_reference, status, "
                        + "reserved_at, check_in_date, check_out_date, currency, total_amount, created_at, created_by, "
                        + "updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, now(), ?::date, ?::date, 'VND', 1, now(), ?, now(), ?)",
                id, number, guest, source, ota, status, checkIn, checkOut, user, user);
        return id;
    }

    private UUID room(String number, UUID typeId) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO NOTHING",
                UUID.randomUUID(), number, typeId, user, user);
        return roomId(number);
    }

    private UUID roomId(String number) {
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID bookedRoom(UUID reservation, String roomNumber, UUID typeId, String checkIn, String checkOut) {
        UUID room = room(roomNumber, typeId);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                + "total_amount, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?::date, ?::date, 1, 1, now(), ?, now(), ?)",
                id, reservation, room, checkIn, checkOut, user, user);
        return id;
    }

    private UUID stay(UUID reservation, String status, String checkedOutAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, actual_check_out_at, created_at, "
                        + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, now(), ?, now(), ?)",
                id, reservation, status, Instant.parse("2026-10-01T10:00:00Z").toString(), checkedOutAt, user, user);
        return id;
    }

    private void assignment(UUID stay, UUID room, UUID reservationRoom, String from, String to) {
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, reservationRoom, from, to, user, user);
    }
}
