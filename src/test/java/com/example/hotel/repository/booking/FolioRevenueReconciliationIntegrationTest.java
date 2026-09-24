package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.FolioReconciliationService;
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
 * Verifies Folio to Revenue reconciliation against PostgreSQL: original ROOM charge links, guest service revenue,
 * reconciliation diagnostics, the folio formula, payment independence of revenue, and the Stay-lock races.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FolioRevenueReconciliationIntegrationTest {

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
    private ChargeService chargeService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StayBalanceService balances;

    @Autowired
    private FolioReconciliationService reconciliation;

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
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NOT NULL");
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
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "rec." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    // ------------------------------------------------------------ original ROOM charge link

    /** Confirms check-in links each ROOM charge to exactly its ReservationRoom, and extension charges are not linked. */
    @Test
    void shouldLinkOriginalRoomChargesAndLeaveExtensionChargesUnlinked() {
        UUID a = room("FR-A", "AVAILABLE");
        UUID b = room("FR-B", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"), new Line(b, "1500000"));
        jdbc.update("UPDATE reservation SET adult_count = 2 WHERE id = ?", reservation);
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);

        assertEquals(2, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM' AND source_reservation_room_id IS NOT NULL", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM charge c JOIN reservation_room rr ON rr.id = c.source_reservation_room_id "
                + "WHERE c.stay_id = ? AND rr.room_id = ? AND c.amount = rr.total_amount AND c.amount = 1000000", stay, a));
        assertEquals(1, count("SELECT COUNT(*) FROM charge c JOIN reservation_room rr ON rr.id = c.source_reservation_room_id "
                + "WHERE c.stay_id = ? AND rr.room_id = ? AND c.amount = 1500000", stay, b));

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(2)));

        assertEquals(2, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM' AND source_reservation_room_id IS NULL", stay),
                "extension ROOM charges never use the source link");
        assertEquals(2, count("SELECT COUNT(*) FROM stay_extension_room l JOIN charge c ON c.id = l.charge_id WHERE c.source_reservation_room_id IS NULL"));
        assertEquals(0, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IS NOT NULL"), "ROOM charges create no revenue rows");
        assertEquals(today.plusDays(1), jdbc.queryForObject("SELECT check_out_date FROM reservation_room WHERE room_id = ?", LocalDate.class, a));
    }

    /** Confirms the database rejects a second original ROOM charge for the same ReservationRoom and non-ROOM sources. */
    @Test
    void shouldRejectDuplicateOriginalChargeAndNonRoomSourceInTheDatabase() {
        UUID a = room("FD-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        UUID line = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);

        assertThrows(DataIntegrityViolationException.class, () -> insertCharge(stay, "ROOM", "1000000", line));
        assertThrows(DataIntegrityViolationException.class, () -> insertCharge(stay, "MINIBAR", "10", line));
        insertCharge(stay, "MINIBAR", "10", null);
    }

    // ------------------------------------------------------------ guest service revenue

    /** Confirms every guest service type creates one linked revenue row and affects the folio but not vice versa. */
    @Test
    void shouldCreateLinkedRevenueForEveryGuestServiceType() {
        UUID a = room("FS-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        BigDecimal before = balances.calculate(stay).outstanding();
        int index = 0;
        for (ChargeType type : List.of(ChargeType.BREAKFAST, ChargeType.EXTRA_BED, ChargeType.LAUNDRY,
                ChargeType.MINIBAR, ChargeType.SERVICE, ChargeType.OTHER)) {
            index++;
            chargeService.create(stay, new ChargeCreateRequest(type, "d" + index, null, null, new BigDecimal(index * 1000)));
        }

        assertEquals(6, count("SELECT COUNT(*) FROM additional_revenue r JOIN charge c ON c.id = r.charge_id WHERE c.stay_id = ? "
                + "AND r.amount = c.amount AND r.payment_method IS NULL AND r.status = 'RECORDED' AND r.currency = 'VND'", stay));
        assertEquals(6, count("SELECT COUNT(DISTINCT r.category_id) FROM additional_revenue r JOIN charge c ON c.id = r.charge_id "
                + "JOIN additional_revenue_category k ON k.id = r.category_id WHERE c.stay_id = ? AND k.code LIKE 'GUEST_%'", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue r JOIN charge c ON c.id = r.charge_id JOIN additional_revenue_category k "
                + "ON k.id = r.category_id WHERE c.stay_id = ? AND c.type = 'MINIBAR' AND k.code = 'GUEST_MINIBAR' "
                + "AND r.revenue_date = (c.charged_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date", stay));
        assertEquals(0, new BigDecimal("21000").add(before).compareTo(balances.calculate(stay).outstanding()),
                "service charges raise the folio; the revenue rows do not");
        assertEquals(6, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IN (SELECT id FROM charge WHERE stay_id = ?)", stay));
        assertEquals("MATCHED", reconciliation.reconcile(stay).status());
    }

    /** Confirms a USD reservation rejects a service charge atomically (nothing recorded). */
    @Test
    void shouldRejectServiceChargeOnAUsdReservationAtomically() {
        UUID a = room("FU-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "100"));
        jdbc.update("UPDATE reservation SET currency = 'USD' WHERE id = ?", reservation);
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        int chargesBefore = count("SELECT COUNT(*) FROM charge WHERE stay_id = ?", stay);

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> chargeService.create(
                stay, new ChargeCreateRequest(ChargeType.LAUNDRY, null, null, null, new BigDecimal("5"))));

        assertEquals(chargesBefore, count("SELECT COUNT(*) FROM charge WHERE stay_id = ?", stay));
        assertEquals(0, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IS NOT NULL"));
    }

    /** Confirms the database keeps revenue one-to-one with a Charge and allows standalone revenue without one. */
    @Test
    void shouldKeepRevenueOneToOneAndAllowStandaloneRevenue() {
        UUID a = room("F1-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        chargeService.create(stay, new ChargeCreateRequest(ChargeType.SERVICE, null, null, null, new BigDecimal("10")));
        UUID charge = jdbc.queryForObject("SELECT id FROM charge WHERE stay_id = ? AND type = 'SERVICE'", UUID.class, stay);
        UUID category = jdbc.queryForObject("SELECT id FROM additional_revenue_category WHERE code = 'GUEST_SERVICE'", UUID.class);

        assertThrows(DataIntegrityViolationException.class, () -> insertRevenue(category, charge, "CASH"));
        insertRevenue(category, null, "CASH");
        assertThrows(DataIntegrityViolationException.class, () -> insertRevenue(category, null, null),
                "payment method may be null only for Charge-linked revenue");
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NULL AND description = 'standalone'");
    }

    // ------------------------------------------------------------ folio and payment independence

    /** Confirms payments and refunds move the folio but never the room revenue; service revenue is counted once. */
    @Test
    void shouldKeepRevenueIndependentOfPaymentsAndCountServiceRevenueOnce() {
        UUID a = room("FP-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        YearMonth month = YearMonth.from(today);
        var report0 = financialReport.report(month);

        chargeService.create(stay, new ChargeCreateRequest(ChargeType.MINIBAR, "cola", null, null, new BigDecimal("50000")));
        var report1 = financialReport.report(month);
        assertEquals(0, new BigDecimal("50000").compareTo(report1.additionalRevenue().subtract(report0.additionalRevenue())),
                "the linked revenue counts once and the Charge is not added again");
        assertEquals(0, report0.roomRevenue().compareTo(report1.roomRevenue()));
        assertEquals(0, new BigDecimal("1050000").compareTo(balances.calculate(stay).totalCharges()));

        var payment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("400000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, new BigDecimal("650000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(0, report1.roomRevenue().compareTo(financialReport.report(month).roomRevenue()));
        assertEquals(0, report1.additionalRevenue().compareTo(financialReport.report(month).additionalRevenue()));

        paymentService.refund(payment.id(), new PaymentRefundRequest("wrong"));
        assertEquals(0, new BigDecimal("1050000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(0, report1.roomRevenue().compareTo(financialReport.report(month).roomRevenue()));
        assertEquals(0, report1.additionalRevenue().compareTo(financialReport.report(month).additionalRevenue()));
    }

    // ------------------------------------------------------------ reconciliation

    /** Confirms normal original charges and original plus extension reconcile as MATCHED. */
    @Test
    void shouldReconcileNormalAndExtendedStaysAsMatched() {
        UUID a = room("RC-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(2), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        assertEquals("MATCHED", reconciliation.reconcile(stay).status());

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(4)));

        FolioReconciliationResponse result = reconciliation.reconcile(stay);
        assertEquals("MATCHED", result.status());
        assertEquals(0, new BigDecimal("4000000").compareTo(result.expectedAccommodationCharges()));
        assertEquals(0, new BigDecimal("4000000").compareTo(result.actualAccommodationCharges()));
        assertEquals(0, new BigDecimal("2000000").compareTo(result.actualExtensionRoomCharges()));
    }

    /** Confirms each constructible mismatch is detected, reconciliation never mutates, and Checkout is not blocked. */
    @Test
    void shouldDetectMismatchesWithoutMutatingOrBlockingCheckout() {
        UUID a = room("RM-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        chargeService.create(stay, new ChargeCreateRequest(ChargeType.SERVICE, null, null, null, new BigDecimal("10")));

        // missing original link -> missing + orphan
        jdbc.update("UPDATE charge SET source_reservation_room_id = NULL WHERE stay_id = ? AND type = 'ROOM'", stay);
        List<FolioReconciliationResponse.IssueType> types = reconciliation.reconcile(stay).issues().stream().map(i -> i.type()).toList();
        assertTrue(types.contains(FolioReconciliationResponse.IssueType.MISSING_ORIGINAL_ROOM_CHARGE));
        assertTrue(types.contains(FolioReconciliationResponse.IssueType.ORPHAN_ROOM_CHARGE));

        // relink, then amount mismatch on the original charge
        UUID line = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservation);
        jdbc.update("UPDATE charge SET source_reservation_room_id = ?, amount = 999 WHERE stay_id = ? AND type = 'ROOM'", line, stay);
        assertTrue(reconciliation.reconcile(stay).issues().stream().anyMatch(
                i -> i.type() == FolioReconciliationResponse.IssueType.ORIGINAL_ROOM_CHARGE_AMOUNT_MISMATCH));
        jdbc.update("UPDATE charge SET amount = 1000000 WHERE stay_id = ? AND type = 'ROOM'", stay);
        assertEquals("MATCHED", reconciliation.reconcile(stay).status());

        // service revenue mismatch and a service charge with no revenue
        jdbc.update("UPDATE additional_revenue SET amount = 1 WHERE charge_id IN (SELECT id FROM charge WHERE stay_id = ?)", stay);
        assertTrue(reconciliation.reconcile(stay).issues().stream().anyMatch(
                i -> i.type() == FolioReconciliationResponse.IssueType.SERVICE_REVENUE_AMOUNT_MISMATCH));
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NOT NULL");
        assertTrue(reconciliation.reconcile(stay).issues().stream().anyMatch(
                i -> i.type() == FolioReconciliationResponse.IssueType.SERVICE_CHARGE_WITHOUT_REVENUE));

        // an orphan ROOM charge
        insertCharge(stay, "ROOM", "5", null);
        assertTrue(reconciliation.reconcile(stay).issues().stream().anyMatch(
                i -> i.type() == FolioReconciliationResponse.IssueType.ORPHAN_ROOM_CHARGE));

        // reading never changes data
        int charges = count("SELECT COUNT(*) FROM charge");
        BigDecimal sum = jdbc.queryForObject("SELECT SUM(amount) FROM charge", BigDecimal.class);
        reconciliation.reconcile(stay);
        assertEquals(charges, count("SELECT COUNT(*) FROM charge"));
        assertEquals(0, sum.compareTo(jdbc.queryForObject("SELECT SUM(amount) FROM charge", BigDecimal.class)));

        // the mismatch does not block Checkout: settle the folio and check out
        BigDecimal outstanding = balances.calculate(stay).outstanding();
        paymentService.recordPaid(stay, new PaymentCreateRequest(outstanding, PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        reservationService.checkOut(reservation);
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation));
        assertEquals("MISMATCH", reconciliation.reconcile(stay).status());
    }

    /** Confirms an extension charge that no longer equals its line is reported. */
    @Test
    void shouldDetectAnExtensionChargeAmountMismatch() {
        UUID a = room("RE-A", "AVAILABLE");
        UUID reservation = confirmed(today, today.plusDays(1), new Line(a, "1000000"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(1), today.plusDays(2)));
        jdbc.update("UPDATE charge SET amount = 7 WHERE id IN (SELECT charge_id FROM stay_extension_room)");

        assertTrue(reconciliation.reconcile(stay).issues().stream().anyMatch(
                i -> i.type() == FolioReconciliationResponse.IssueType.EXTENSION_CHARGE_AMOUNT_MISMATCH));
    }

    // ------------------------------------------------------------ concurrency

    /** Confirms Charge creation and Checkout serialize: a stay is never CHECKED_OUT with a racing new Charge. */
    @Test
    void shouldSerializeChargeCreationAndCheckOut() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            setUp();
            UUID a = room("RX-A", "OCCUPIED");
            UUID stay = seededCheckedIn(a, today, today.plusDays(1));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> chargeService.create(stay, new ChargeCreateRequest(ChargeType.SERVICE, null, null, null, new BigDecimal("10"))),
                    () -> reservationService.checkOut(reservation));

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
            int services = count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'SERVICE'", stay);
            assertTrue("CHECKED_OUT".equals(status) ? services == 0 : services == 1, "no charge behind a completed check-out");
            assertEquals(services, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IN (SELECT id FROM charge WHERE stay_id = ?)", stay));
        }
    }

    /** Confirms a Payment creation and Checkout serialize through the Stay lock. */
    @Test
    void shouldSerializePaymentCreationAndCheckOut() throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            setUp();
            UUID a = room("RY-A", "OCCUPIED");
            UUID stay = seededCheckedIn(a, today, today.plusDays(1));
            UUID reservation = reservationOf(stay);

            List<Object> results = race(
                    () -> paymentService.create(stay, new PaymentCreateRequest(
                            new BigDecimal("10"), PaymentCurrency.VND, null, PaymentMethod.CASH, null)),
                    () -> reservationService.checkOut(reservation));

            assertTrue(results.stream().filter(RuntimeException.class::isInstance).allMatch(
                    r -> r instanceof org.springframework.web.server.ResponseStatusException), results.toString());
            String status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
            assertEquals("CHECKED_OUT", status, "a zero-balance stay always ends up checked out");
            if (count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay) == 1) {
                assertTrue(jdbc.queryForObject("SELECT p.created_at <= s.actual_check_out_at FROM payment p JOIN stay s ON s.id = p.stay_id "
                        + "WHERE p.stay_id = ?", Boolean.class, stay), "a payment only exists if it committed before the check-out");
            }
        }
    }

    /** Confirms Charge creation and Stay Extension / Room Change share the Stay lock without deadlock. */
    @Test
    void shouldNotDeadlockChargeWithExtensionOrRoomChange() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            setUp();
            UUID a = room("RZ-A", "OCCUPIED");
            UUID b = room("RZ-B", "AVAILABLE");
            UUID stay = seededCheckedIn(a, today, today.plusDays(2));
            UUID reservation = reservationOf(stay);

            List<Object> first = race(
                    () -> chargeService.create(stay, new ChargeCreateRequest(ChargeType.SERVICE, null, null, null, new BigDecimal("10"))),
                    () -> extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(3))));
            assertTrue(first.stream().noneMatch(RuntimeException.class::isInstance), first.toString());
            List<Object> second = race(
                    () -> chargeService.create(stay, new ChargeCreateRequest(ChargeType.LAUNDRY, null, null, null, new BigDecimal("10"))),
                    () -> roomChangeService.changeRoom(reservation, a, new com.example.hotel.dto.booking.request.RoomChangeRequest(
                            b, com.example.hotel.entity.booking.RoomChangeReason.GUEST_REQUEST, null)));
            assertTrue(second.stream().noneMatch(RuntimeException.class::isInstance), second.toString());
        }
    }

    private void insertCharge(UUID stay, String type, String amount, UUID source) {
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, charged_at, created_at, created_by, "
                + "updated_at, updated_by, source_reservation_room_id) VALUES (?, ?, ?, 'x', NULL, NULL, ?, now(), now(), ?, now(), ?, ?)",
                UUID.randomUUID(), stay, type, new BigDecimal(amount), user, user, source);
    }

    private void insertRevenue(UUID category, UUID charge, String method) {
        jdbc.update("INSERT INTO additional_revenue (id, category_id, amount, currency, revenue_date, payment_method, description, status, "
                + "charge_id, created_at, created_by, updated_at, updated_by) VALUES (?, ?, 10, 'VND', ?, ?, 'standalone', 'RECORDED', ?, "
                + "now(), ?, now(), ?)", UUID.randomUUID(), category, today, method, charge, user, user);
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
        // Midnight of the check-in day, never a fixed wall-clock hour: every caller passes in <= today, so this is
        // always <= any later real Instant.now(clock) a production close (Room Change, checkout) computes the same
        // day - unlike a fixed hour such as 14:00, which is in the future whenever the suite runs before it.
        Instant from = in.atStartOfDay(ZONE).toInstant();
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
