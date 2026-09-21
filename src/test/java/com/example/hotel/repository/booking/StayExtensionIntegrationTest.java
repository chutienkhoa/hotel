package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.RoomChangeService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.common.MonthlyFinancialReportService;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * Verifies Stay Extension against PostgreSQL: persisted effects, immutability of the original snapshot, lineage and
 * actual-room snapshots, ROOM charges and the folio, availability (including self-exclusion), Front Desk and check-out
 * consumers, the Financial Report, the schema constraints, and the lock-based races.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StayExtensionIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StayExtensionService extensionService;

    @Autowired
    private RoomChangeService roomChangeService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RoomAvailabilityService availability;

    @Autowired
    private StayBalanceService balances;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FrontDeskQueryService frontDesk;

    @Autowired
    private CheckOutQueryService checkOutQuery;

    @Autowired
    private StayRepository stayRepository;

    @Autowired
    private MonthlyFinancialReportService financialReport;

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
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHANGE_ROOM', 'CONFIRM_RESERVATION', 'EXTEND_STAY')");
        jdbc.update("DELETE FROM stay_extension_room");
        jdbc.update("DELETE FROM stay_extension");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "ext." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    // ---------------------------------------------------------------- end to end

    /** Confirms the full effect: date moved, snapshot and total untouched, history, charge, folio, audit. */
    @Test
    void shouldExtendAndPersistEveryEffect() {
        UUID a = room("XE-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(2), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        assertEquals(0, new BigDecimal("2000000").compareTo(balances.calculate(stay).totalCharges()));

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4)));

        assertEquals(today.plusDays(4), checkOutOf(reservation));
        assertEquals(today.plusDays(2), jdbc.queryForObject("SELECT check_out_date FROM reservation_room WHERE reservation_id = ?", LocalDate.class, reservation));
        assertEquals(0, new BigDecimal("2000000").compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM reservation WHERE id = ?", BigDecimal.class, reservation)));
        assertEquals(0, new BigDecimal("2000000").compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM reservation_room WHERE reservation_id = ?", BigDecimal.class, reservation)));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ? AND sequence_no = 1 AND previous_check_out_date = ? "
                + "AND new_check_out_date = ?", stay, today.plusDays(2), today.plusDays(4)));
        assertEquals(2, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM' AND amount = 2000000 AND quantity = 2 "
                + "AND unit_price = 1000000 AND description LIKE 'Room XE-A extension%'", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room l JOIN charge c ON c.id = l.charge_id "
                + "WHERE c.stay_id = ? AND l.room_id = ? AND l.amount = 2000000 AND l.nightly_rate = 1000000 "
                + "AND l.from_date = ? AND l.to_date = ?", stay, a, today.plusDays(2), today.plusDays(4)));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'EXTEND_STAY' AND entity_id = ?", reservation));
        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(0, new BigDecimal("2000000").compareTo(extensionService.summary(reservation).extensionAmount()));
        assertEquals(0, new BigDecimal("4000000").compareTo(extensionService.summary(reservation).currentAccommodationTotal()));
    }

    /** Confirms a fully paid stay becomes outstanding, the payment is unchanged and departure needs payment. */
    @Test
    void shouldMakeAPaidStayOutstandingWithoutGatingOnPayment() {
        UUID a = room("XF-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("1000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, BigDecimal.ZERO.compareTo(balances.calculate(stay).outstanding()));

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(2)));
        assertEquals(0, new BigDecimal("1000000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(0, new BigDecimal("1000000").compareTo(balances.calculate(stay).totalPaidPayments()));
        assertTrue(frontDesk.inHouse().stream().anyMatch(row -> row.reservationId().equals(reservation)));

        // Non-zero balance does not gate a further extension.
        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(3)));
        assertEquals(0, new BigDecimal("2000000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(2, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
    }

    /** Confirms sequential extensions chain (1 then 2), history is preserved, and a stale expected date is rejected. */
    @Test
    void shouldChainSequentialExtensionsAndRejectStaleRequests() {
        UUID a = room("XS-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(2), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4)));
        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(4), today.plusDays(5)));

        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT sequence_no FROM stay_extension WHERE stay_id = ? ORDER BY sequence_no", Integer.class, stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ? AND sequence_no = 2 "
                + "AND previous_check_out_date = ? AND new_check_out_date = ?", stay, today.plusDays(4), today.plusDays(5)));
        assertEquals(3, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
        StayExtensionException stale = assertThrows(StayExtensionException.class, () -> extensionService.extend(
                reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(6))));
        assertEquals(Reason.STALE_CHECK_OUT_DATE, stale.getExtensionReason());
        assertEquals(2, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
        assertEquals(today.plusDays(5), checkOutOf(reservation));
    }

    /** Confirms a multi-room stay gets a line and a charge per lineage, each at its own original rate. */
    @Test
    void shouldExtendEveryLineageOfAMultiRoomStay() {
        UUID a = room("XM-A", "AVAILABLE");
        UUID b = room("XM-B", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"), new Line(b, "1500000"));
        jdbc.update("UPDATE reservation SET adult_count = 2 WHERE id = ?", reservation);
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(3)));

        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
        assertEquals(2, count("SELECT COUNT(*) FROM stay_extension_room l JOIN stay_extension e ON e.id = l.stay_extension_id "
                + "WHERE e.stay_id = ?", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE room_id = ? AND amount = 2000000", a));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE room_id = ? AND amount = 3000000", b));
        assertEquals(0, new BigDecimal("5000000").compareTo(extensionService.summary(reservation).extensionAmount()));
    }

    // ---------------------------------------------------------------- room change

    /** Confirms A to B before extension: lineage A, actual room B, B protected, A free; RR unchanged; later C works. */
    @Test
    void shouldKeepLineageAndActualRoomAcrossRoomChangesAndExtensions() {
        UUID a = room("XR-A", "AVAILABLE");
        UUID b = room("XR-B", "AVAILABLE");
        UUID c = room("XR-C", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(2), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        UUID lineage = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        // The stay began in the past so that Room Change (today < planned check-out) is allowed: check-out is tomorrow+.
        roomChangeService.changeRoom(reservation, a, new RoomChangeRequest(b, RoomChangeReason.GUEST_REQUEST, null));

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4)));

        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE original_reservation_room_id = ? AND room_id = ?", lineage, b));
        assertTrue(availability.hasInventoryConflict(b, today.plusDays(2), today.plusDays(4)), "B protected through the new check-out");
        assertFalse(availability.hasInventoryConflict(a, today.plusDays(2), today.plusDays(4)));
        assertEquals(today.plusDays(2), jdbc.queryForObject("SELECT check_out_date FROM reservation_room WHERE id = ?", LocalDate.class, lineage));

        markCleaned(a);
        roomChangeService.changeRoom(reservation, b, new RoomChangeRequest(c, RoomChangeReason.GUEST_REQUEST, null));
        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(4), today.plusDays(5)));

        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE original_reservation_room_id = ? AND room_id = ?", lineage, b),
                "the earlier extension still says B");
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE original_reservation_room_id = ? AND room_id = ? AND amount = 1000000", lineage, c),
                "the later extension snapshots C at the lineage rate");
        assertTrue(availability.hasInventoryConflict(c, today.plusDays(4), today.plusDays(5)));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? AND assigned_to IS NULL", stay));
    }

    /** Confirms the extension rate comes from the original lineage, not from the current room type price. */
    @Test
    void shouldIgnoreTheCurrentRoomTypePrice() {
        UUID a = room("XP-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "777000"));
        reservationService.checkIn(reservation);

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(3)));

        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE nightly_rate = 777000 AND amount = 1554000"));
    }

    // ---------------------------------------------------------------- availability

    /** Confirms a future CONFIRMED reservation on the current room blocks the extension; nothing is written. */
    @Test
    void shouldRejectWhenAFutureConfirmedReservationHoldsTheRoom() {
        UUID a = room("XV-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        confirmed(today.plusDays(2), today.plusDays(3), new Line(a, "1000000"));

        StayExtensionException exception = assertThrows(StayExtensionException.class, () -> extensionService.extend(
                reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(3))));

        assertEquals(Reason.INVENTORY_CONFLICT, exception.getExtensionReason());
        assertEquals(today.plusDays(1), checkOutOf(reservation));
        assertEquals(0, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE stay_id = ?", stay));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'EXTEND_STAY'"));
    }

    /** Confirms a stay never conflicts with itself: same-day planned check-out and overdue extensions succeed. */
    @Test
    void shouldNotSelfConflictOnTheCheckOutDayOrWhenOverdue() {
        UUID a = room("XD-A", "OCCUPIED");
        UUID sameDay = seededCheckedIn(a, today.minusDays(1), today);
        extensionService.extend(reservationOf(sameDay), new StayExtensionRequest(today, today.plusDays(1)));
        assertEquals(today.plusDays(1), checkOutOf(reservationOf(sameDay)));
        assertTrue(availability.hasInventoryConflict(a, today, today.plusDays(1)), "protected through the new date");
        assertFalse(availability.hasInventoryConflict(a, today.plusDays(1), today.plusDays(2)), "and not beyond it");

        UUID b = room("XD-B", "OCCUPIED");
        UUID overdue = seededCheckedIn(b, today.minusDays(3), today.minusDays(1));
        extensionService.extend(reservationOf(overdue), new StayExtensionRequest(today.minusDays(1), today.plusDays(1)));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room l JOIN stay_extension e ON e.id = l.stay_extension_id "
                + "WHERE e.stay_id = ? AND l.from_date = ? AND l.to_date = ? AND l.amount = 2000000", overdue, today.minusDays(1), today.plusDays(1)),
                "the overdue night is billed from the old planned check-out");
        assertTrue(availability.hasInventoryConflict(b, today, today.plusDays(1)), "protected through the new date");
    }

    /** Confirms an overdue request with new date not after today is rejected. */
    @Test
    void shouldRejectAnOverdueRequestThatDoesNotPassToday() {
        UUID a = room("XO-A", "OCCUPIED");
        UUID stay = seededCheckedIn(a, today.minusDays(3), today.minusDays(1));

        StayExtensionException exception = assertThrows(StayExtensionException.class, () -> extensionService.extend(
                reservationOf(stay), new StayExtensionRequest(today.minusDays(1), today)));

        assertEquals(Reason.INVALID_NEW_CHECK_OUT_DATE, exception.getExtensionReason());
    }

    // ---------------------------------------------------------------- consumers

    /** Confirms Front Desk, the check-out review and the dashboard count follow the new planned departure. */
    @Test
    void shouldMoveTheDepartureInEveryConsumer() {
        UUID a = room("XC-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        assertEquals(1, stayRepository.countCheckedInScheduledForCheckOutOn(StayStatus.CHECKED_IN, today.plusDays(1)));
        assertEquals(today.plusDays(1), checkOutQuery.review(reservation).checkOutDate());

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(3)));

        assertEquals(0, stayRepository.countCheckedInScheduledForCheckOutOn(StayStatus.CHECKED_IN, today.plusDays(1)));
        assertEquals(1, stayRepository.countCheckedInScheduledForCheckOutOn(StayStatus.CHECKED_IN, today.plusDays(3)));
        assertEquals(today.plusDays(3), checkOutQuery.review(reservation).checkOutDate());
        FrontDeskStayRow row = frontDesk.inHouse().stream().filter(r -> r.reservationId().equals(reservation)).findFirst().orElseThrow();
        assertEquals(today.plusDays(3), row.plannedCheckOutDate());
        assertFalse(row.overdue());
    }

    /** Confirms an overdue stay stops being overdue once extended past today. */
    @Test
    void shouldClearOverdueAfterExtension() {
        UUID a = room("XL-A", "OCCUPIED");
        UUID stay = seededCheckedIn(a, today.minusDays(3), today.minusDays(1));
        assertTrue(frontDesk.inHouse().stream().filter(r -> r.reservationId().equals(reservationOf(stay))).findFirst().orElseThrow().overdue());

        extensionService.extend(reservationOf(stay), new StayExtensionRequest(today.minusDays(1), today.plusDays(2)));

        assertFalse(frontDesk.inHouse().stream().filter(r -> r.reservationId().equals(reservationOf(stay))).findFirst().orElseThrow().overdue());
    }

    // ---------------------------------------------------------------- reports

    /** Confirms the Financial Report adds extension revenue once, splits months, and leaves the original untouched. */
    @Test
    void shouldIncludeExtensionRevenueInTheFinancialReport() {
        LocalDate in = LocalDate.of(2031, 9, 26);
        LocalDate out = LocalDate.of(2031, 9, 28);
        UUID a = room("XQ-A", "OCCUPIED");
        UUID stay = seededCheckedIn(a, in, out);
        // CHECKED_OUT keeps the contracted nights; a CHECKED_IN stay in 2031 would recognize none of them yet.
        jdbc.update("UPDATE reservation SET status = 'CHECKED_OUT' WHERE id = ?", reservationOf(stay));
        BigDecimal before = financialReport.report(YearMonth.of(2031, 9)).roomRevenue();
        assertEquals(0, new BigDecimal("2000000").compareTo(before));

        insertExtension(stay, a, out, LocalDate.of(2031, 10, 2), 1);

        assertEquals(0, new BigDecimal("2000000").add(new BigDecimal("3000000")).compareTo(
                financialReport.report(YearMonth.of(2031, 9)).roomRevenue()), "original 2 nights + Sep 28..30 extension");
        assertEquals(0, new BigDecimal("1000000").compareTo(financialReport.report(YearMonth.of(2031, 10)).roomRevenue()),
                "the Oct 1 extension night");
        assertEquals(0, new BigDecimal("2000000").compareTo(jdbc.queryForObject(
                "SELECT total_amount FROM reservation_room WHERE reservation_id = ?", BigDecimal.class, reservationOf(stay))));
    }

    // ---------------------------------------------------------------- schema

    /** Confirms the schema enforces the extension constraints (dates, uniqueness, positive amount, charge uniqueness). */
    @Test
    void shouldEnforceTheExtensionSchemaConstraints() {
        UUID a = room("XK-A", "OCCUPIED");
        UUID stay = seededCheckedIn(a, today, today.plusDays(2));
        UUID ext = insertRawExtension(stay, 1, today.plusDays(2), today.plusDays(4));

        assertThrows(DataIntegrityViolationException.class, () -> insertRawExtension(stay, 2, today.plusDays(4), today.plusDays(4)),
                "new must be after previous");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawExtension(stay, 1, today.plusDays(4), today.plusDays(5)),
                "sequence unique per stay");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawExtension(stay, 2, today.plusDays(2), today.plusDays(5)),
                "base date unique per stay (no branching)");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawExtension(UUID.randomUUID(), 3, today.plusDays(4), today.plusDays(5)),
                "stay FK");

        UUID lineage = jdbc.queryForObject("SELECT original_reservation_room_id FROM stay_room_assignment WHERE stay_id = ?", UUID.class, stay);
        UUID charge = insertCharge(stay, "2000000");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawLine(ext, lineage, a, today.plusDays(2), today.plusDays(2), "1000000", "0", charge),
                "amount and dates positive");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawLine(ext, lineage, a, today.plusDays(2), today.plusDays(4), "1000000", "0", charge),
                "amount must be positive");
        assertThrows(DataIntegrityViolationException.class, () -> insertRawLine(ext, lineage, a, today.plusDays(2), today.plusDays(4), "1000000", "2000000", UUID.randomUUID()),
                "charge FK");
        insertRawLine(ext, lineage, a, today.plusDays(2), today.plusDays(4), "1000000", "2000000", charge);
        assertThrows(DataIntegrityViolationException.class, () -> insertRawLine(ext, lineage, a, today.plusDays(2), today.plusDays(4), "1000000", "2000000", insertCharge(stay, "2000000")),
                "one line per lineage per event");
    }

    // ---------------------------------------------------------------- concurrency

    /** Confirms Extend and Confirm on the same future room serialize: exactly one wins, no conflict remains. */
    @Test
    void shouldSerializeExtendAndConfirm() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            setUp();
            UUID b = room("XZ-B", "AVAILABLE");
            UUID stay = seededCheckedIn(b, today, today.plusDays(1));
            UUID r1 = reservationOf(stay);
            UUID r2 = draft(today.plusDays(2), today.plusDays(3), new Line(b, "1000000"));

            List<Object> results = race(
                    () -> extensionService.extend(r1, new StayExtensionRequest(today.plusDays(1), today.plusDays(3))),
                    () -> reservationService.confirm(r2));

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            boolean extended = count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay) == 1;
            boolean confirmed = "CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, r2));
            assertTrue(extended ^ confirmed, "never both, never neither");
            assertEquals(extended ? today.plusDays(3) : today.plusDays(1), checkOutOf(r1));
        }
    }

    /** Confirms two racing extensions never branch the history or duplicate the charge. */
    @Test
    void shouldSerializeExtendAndExtend() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            setUp();
            UUID a = room("XY-A", "AVAILABLE");
            UUID stay = seededCheckedIn(a, today, today.plusDays(1));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(3))),
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(4))));

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            assertTrue(results.stream().filter(RuntimeException.class::isInstance)
                    .allMatch(r -> r instanceof StayExtensionException e && e.getExtensionReason() == Reason.STALE_CHECK_OUT_DATE), results.toString());
            assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
            assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
        }
    }

    /** Confirms Extend and Check-out serialize on the Stay lock: exactly one path proceeds. */
    @Test
    void shouldSerializeExtendAndCheckOut() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            setUp();
            UUID a = room("XW-A", "OCCUPIED");
            UUID stay = seededCheckedIn(a, today, today.plusDays(1));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(2))),
                    () -> reservationService.checkOut(reservation));

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            boolean extended = count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay) == 1;
            String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
            assertEquals(extended ? "CHECKED_IN" : "CHECKED_OUT", status);
            assertEquals(extended ? 1 : 0, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
        }
    }

    /** Confirms Extend and Room Change serialize on the Stay lock and the line snapshots the actual room. */
    @Test
    void shouldSerializeExtendAndRoomChange() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            setUp();
            UUID a = room("XU-A", "OCCUPIED");
            UUID b = room("XU-B", "AVAILABLE");
            UUID stay = seededCheckedIn(a, today, today.plusDays(2));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4))),
                    () -> roomChangeService.changeRoom(reservation, a, new RoomChangeRequest(b, RoomChangeReason.GUEST_REQUEST, null)));

            assertTrue(results.stream().allMatch(r -> !(r instanceof RuntimeException)), "both may succeed: " + results);
            UUID lineRoom = jdbc.queryForObject("SELECT room_id FROM stay_extension_room l JOIN stay_extension e ON e.id = l.stay_extension_id "
                    + "WHERE e.stay_id = ?", UUID.class, stay);
            Timestamp lineAt = jdbc.queryForObject("SELECT l.created_at FROM stay_extension_room l JOIN stay_extension e "
                    + "ON e.id = l.stay_extension_id WHERE e.stay_id = ?", Timestamp.class, stay);
            Timestamp moveAt = jdbc.queryForObject("SELECT created_at FROM stay_room_assignment WHERE stay_id = ? AND room_id = ?",
                    Timestamp.class, stay, b);
            if (lineRoom.equals(a)) {
                assertTrue(lineAt.before(moveAt), "extension ran first, so it snapshots A");
            } else {
                assertEquals(b, lineRoom);
                assertTrue(moveAt.before(lineAt), "room change ran first, so it snapshots B");
            }
            assertTrue(availability.hasInventoryConflict(b, today.plusDays(2), today.plusDays(4)));
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Line(UUID room, String rate) {}

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

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private void markCleaned(UUID room) {
        jdbc.update("UPDATE room SET status = 'AVAILABLE' WHERE id = ?", room);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private LocalDate checkOutOf(UUID reservation) {
        return jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation);
    }

    private UUID stayOf(UUID reservation) {
        return jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
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

    private UUID confirmed(LocalDate in, LocalDate out, Line... lines) {
        return reservation("CONFIRMED", in, out, lines);
    }

    private UUID draft(LocalDate in, LocalDate out, Line... lines) {
        return reservation("DRAFT", in, out, lines);
    }

    private UUID reservation(String status, LocalDate in, LocalDate out, Line... lines) {
        UUID id = UUID.randomUUID();
        BigDecimal total = BigDecimal.ZERO;
        long nights = java.time.temporal.ChronoUnit.DAYS.between(in, out);
        for (Line line : lines) {
            total = total.add(new BigDecimal(line.rate()).multiply(BigDecimal.valueOf(nights)));
        }
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', ?, now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "XE" + id.toString().substring(0, 12), guest, status, in, out, total, user, user);
        for (Line line : lines) {
            jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                    + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                    UUID.randomUUID(), id, line.room(), in, out, new BigDecimal(line.rate()),
                    new BigDecimal(line.rate()).multiply(BigDecimal.valueOf(nights)), user, user);
        }
        return id;
    }

    /** Seeds a CHECKED_IN reservation, Stay and open assignment (no original charge); returns the Stay id. */
    private UUID seededCheckedIn(UUID room, LocalDate in, LocalDate out) {
        UUID reservation = reservation("CHECKED_IN", in, out, new Line(room, "1000000"));
        UUID stay = UUID.randomUUID();
        Instant from = in.atTime(14, 0).atZone(ZONE).toInstant();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', ?, now(), ?, now(), ?)", stay, reservation, Timestamp.from(from), user, user);
        UUID lineId = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("INSERT INTO stay_room_assignment (id, stay_id, room_id, original_reservation_room_id, assigned_from, "
                + "assigned_to, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, NULL, now(), ?, now(), ?)",
                UUID.randomUUID(), stay, room, lineId, Timestamp.from(from), user, user);
        jdbc.update("UPDATE room SET status = 'OCCUPIED' WHERE id = ?", room);
        return stay;
    }

    private UUID insertCharge(UUID stay, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, charged_at, created_at, created_by, "
                + "updated_at, updated_by) VALUES (?, ?, 'ROOM', 'x', 2, 1000000, ?, now(), now(), ?, now(), ?)",
                id, stay, new BigDecimal(amount), user, user);
        return id;
    }

    private UUID insertExtension(UUID stay, UUID room, LocalDate from, LocalDate to, int sequence) {
        UUID ext = insertRawExtension(stay, sequence, from, to);
        UUID lineage = jdbc.queryForObject("SELECT original_reservation_room_id FROM stay_room_assignment WHERE stay_id = ?", UUID.class, stay);
        long nights = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        BigDecimal amount = new BigDecimal("1000000").multiply(BigDecimal.valueOf(nights));
        insertRawLine(ext, lineage, room, from, to, "1000000", amount.toPlainString(), insertCharge(stay, amount.toPlainString()));
        return ext;
    }

    private UUID insertRawExtension(UUID stay, int sequence, LocalDate previous, LocalDate next) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stay_extension (id, stay_id, sequence_no, previous_check_out_date, new_check_out_date, created_at, "
                + "created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?)",
                id, stay, sequence, previous, next, user, user);
        return id;
    }

    private void insertRawLine(UUID ext, UUID lineage, UUID room, LocalDate from, LocalDate to, String rate, String amount, UUID charge) {
        jdbc.update("INSERT INTO stay_extension_room (id, stay_extension_id, original_reservation_room_id, room_id, from_date, to_date, "
                + "nightly_rate, amount, charge_id, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), ext, lineage, room, from, to, new BigDecimal(rate), new BigDecimal(amount), charge, user, user);
    }
}
