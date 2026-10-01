package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.ChargeVoidRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.common.MonthlyFinancialReportService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies Charge / Payment Void Correction V1 against PostgreSQL: balance exclusion, the atomic
 * Charge-linked Additional Revenue coordination, reconciliation, reports, checkout readiness, and the
 * post-checkout financial immutability boundary, plus the migration's DB CHECK constraints.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ChargePaymentVoidIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ChargeService chargeService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReservationService reservationService;

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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHECK_IN', 'CHECK_OUT', 'CONFIRM', 'RECORD_CHARGE', "
                + "'RECORD_PAYMENT', 'VOID_CHARGE', 'VOID_PAYMENT', 'REFUND_PAYMENT')");
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NOT NULL");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "void." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /** Checks in a fresh 2-night, 2,000,000/night reservation and returns its Stay id. */
    private UUID checkedInStay() {
        UUID room = room("VD-" + UUID.randomUUID().toString().substring(0, 6));
        UUID reservation = confirmed(room);
        reservationService.checkIn(reservation);
        return jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
    }

    /** Confirms a VOIDED manual Charge stops counting toward Total Charges/Outstanding, but the row remains present. */
    @Test
    void shouldExcludeVoidedChargeFromBalance() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(ChargeType.MINIBAR, "cola", null, null, new BigDecimal("100000")));
        BigDecimal outstandingBefore = balances.calculate(stay).outstanding();

        chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Duplicate entry"));

        assertEquals(0, outstandingBefore.subtract(new BigDecimal("100000")).compareTo(balances.calculate(stay).outstanding()));
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE id = ? AND status = 'VOIDED'", charge.id()), "row remains, now VOIDED");
    }

    /**
     * Confirms the approved negative-balance guard against real PostgreSQL: with the folio fully settled, voiding a
     * Charge would drive Outstanding below zero, so it is rejected and NOTHING changes -- the Charge stays ACTIVE, its
     * linked Additional Revenue stays RECORDED, the balance is untouched and no VOID_CHARGE audit row is written.
     * V1 has no OVERPAID folio state.
     */
    @Test
    void shouldRejectChargeVoidThatWouldMakeOutstandingNegative() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "cola", null, null, new BigDecimal("200000")));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4200000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "folio is fully settled");

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake")));

        assertEquals(409, rejected.getStatusCode().value());
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE id = ? AND status = 'ACTIVE'", charge.id()),
                "the Charge is still ACTIVE");
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id = ? AND status = 'RECORDED'", charge.id()),
                "the linked Additional Revenue is untouched");
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "the balance is unchanged");
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'VOID_CHARGE' AND entity_id = ?",
                reservationOf(stay)), "a rejected void writes no success audit record");
    }

    /** Confirms a void landing exactly on Outstanding zero is allowed: Charges 4,200,000, PAID 4,000,000, void 200,000. */
    @Test
    void shouldAllowChargeVoidThatLeavesOutstandingExactlyZero() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "cola", null, null, new BigDecimal("200000")));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));

        chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake"));

        assertEquals(0, balances.calculate(stay).outstanding().signum(), "Outstanding lands exactly on zero");
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE id = ? AND status = 'VOIDED'", charge.id()));
    }

    /** Confirms a void leaving positive Outstanding is allowed: Charges 4,200,000, PAID 3,800,000, void 200,000. */
    @Test
    void shouldAllowChargeVoidThatLeavesOutstandingPositive() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "cola", null, null, new BigDecimal("200000")));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("3800000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));

        chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake"));

        assertEquals(0, new BigDecimal("200000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE id = ? AND status = 'VOIDED'", charge.id()));
    }

    /**
     * Confirms the approved operational correction order works end to end: void the erroneous Payment first, then the
     * erroneous Charge, and the guest can still check out. This is the documented recovery from the rejection above.
     */
    @Test
    void shouldAllowChargeVoidAfterTheErroneousPaymentIsVoidedFirst() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "cola", null, null, new BigDecimal("200000")));
        var payment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4200000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertThrows(ResponseStatusException.class,
                () -> chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake")));

        paymentService.voidPayment(payment.id(), new PaymentVoidRequest("Wrong amount entered"));
        chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake"));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));

        assertEquals(0, balances.calculate(stay).outstanding().signum());
        reservationService.checkOut(reservationOf(stay));
        assertEquals("CHECKED_OUT", statusOf(reservationOf(stay)), "the corrected folio checks out normally");
    }

    /**
     * Confirms Charge void and Payment recording are serialized by the shared Stay row lock, so the negative-balance
     * invariant cannot be defeated by concurrency. Outstanding starts at 200,000; voiding the 200,000 Charge and
     * recording a 200,000 Payment are each individually valid, but together they would leave Outstanding at -200,000.
     * Exactly one may commit, and the folio never ends up overpaid.
     */
    @Test
    void shouldSerializeChargeVoidWithPaymentRecordingSoOutstandingNeverGoesNegative() throws Exception {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "cola", null, null, new BigDecimal("200000")));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, new BigDecimal("200000").compareTo(balances.calculate(stay).outstanding()));

        List<Object> results = race(
                () -> chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Entered by mistake")),
                () -> paymentService.recordPaid(stay, new PaymentCreateRequest(
                        new BigDecimal("200000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null)));

        long winners = results.stream().filter(result -> !(result instanceof RuntimeException)).count();
        assertEquals(1, winners, "exactly one of void/payment may commit: " + results);
        assertTrue(balances.calculate(stay).outstanding().signum() >= 0,
                "Outstanding is never negative: " + balances.calculate(stay).outstanding());
    }

    /** Confirms voiding a manual guest-service Charge atomically voids its linked Additional Revenue, and reports drop it. */
    @Test
    void shouldAtomicallyVoidLinkedAdditionalRevenueAndExcludeFromReports() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(ChargeType.MINIBAR, "cola", null, null, new BigDecimal("100000")));
        YearMonth month = YearMonth.from(today);
        var before = financialReport.report(month);
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id = ? AND status = 'RECORDED'", charge.id()));

        chargeService.voidCharge(charge.id(), new ChargeVoidRequest("Never actually served"));

        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id = ? AND status = 'VOIDED'", charge.id()));
        var after = financialReport.report(month);
        assertEquals(0, before.additionalRevenue().subtract(new BigDecimal("100000")).compareTo(after.additionalRevenue()));
        FolioReconciliationResponse healthy = reconciliation.reconcile(stay);
        assertTrue(healthy.issues().stream().noneMatch(issue -> issue.subjectId().equals(charge.id())), "correctly voided pair is healthy");
    }

    /** Confirms a ROOM Charge (original check-in billing) can never be voided. */
    @Test
    void shouldRejectVoidingRoomCharge() {
        UUID stay = checkedInStay();
        UUID roomCharge = jdbc.queryForObject("SELECT id FROM charge WHERE stay_id = ? AND type = 'ROOM'", UUID.class, stay);

        assertThrows(ResponseStatusException.class,
                () -> chargeService.voidCharge(roomCharge, new ChargeVoidRequest("attempt")));
        assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE id = ? AND status = 'ACTIVE'", roomCharge));
    }

    /** Confirms a Charge cannot be voided once its Stay has checked out (post-checkout financial immutability). */
    @Test
    void shouldRejectChargeVoidAfterCheckOut() {
        UUID stay = checkedInStay();
        var charge = chargeService.create(stay, new ChargeCreateRequest(ChargeType.MINIBAR, "cola", null, null, new BigDecimal("100000")));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                balances.calculate(stay).outstanding(), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        reservationService.checkOut(reservationOf(stay));

        assertThrows(ResponseStatusException.class,
                () -> chargeService.voidCharge(charge.id(), new ChargeVoidRequest("too late")));
    }

    /** Confirms a VOIDED Payment stops counting toward Total Payments/Outstanding, and Outstanding becomes positive again. */
    @Test
    void shouldExcludeVoidedPaymentFromBalanceAndAllowCorrection() {
        UUID stay = checkedInStay();
        var payment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "fully paid");

        paymentService.voidPayment(payment.id(), new PaymentVoidRequest("Wrong amount entered"));

        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).outstanding()), "outstanding again");
        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE id = ? AND status = 'VOIDED'", payment.id()));
        // Staff records the correct payment afterward.
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum());
    }

    /** Confirms REFUND and VOID remain distinct: only one Payment row per action, correct status/reason/AuditLog each. */
    @Test
    void shouldKeepRefundAndVoidDistinct() {
        UUID stay = checkedInStay();
        var refunded = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("1000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        var voided = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("500000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));

        paymentService.refund(refunded.id(), new com.example.hotel.dto.booking.request.PaymentRefundRequest("Guest cancelled stay"));
        paymentService.voidPayment(voided.id(), new PaymentVoidRequest("Duplicate of the first payment"));

        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE id = ? AND status = 'REFUNDED' AND refund_reason IS NOT NULL AND void_reason IS NULL", refunded.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE id = ? AND status = 'VOIDED' AND void_reason IS NOT NULL AND refund_reason IS NULL", voided.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REFUND_PAYMENT' AND old_value LIKE ?", "%" + refunded.id() + "%"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'VOID_PAYMENT' AND old_value LIKE ?", "%" + voided.id() + "%"));
    }

    /** Confirms a Payment cannot be voided once its Stay has checked out. */
    @Test
    void shouldRejectPaymentVoidAfterCheckOut() {
        UUID stay = checkedInStay();
        var payment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        reservationService.checkOut(reservationOf(stay));

        assertThrows(ResponseStatusException.class,
                () -> paymentService.voidPayment(payment.id(), new PaymentVoidRequest("too late")));
    }

    /** Confirms checkout readiness reacts to a corrected balance: blocked after void, ready again once corrected. */
    @Test
    void shouldReactToCorrectedBalanceForCheckoutReadiness() {
        UUID stay = checkedInStay();
        var payment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        UUID reservation = reservationOf(stay);
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "fully paid, checkout would be allowed");

        paymentService.voidPayment(payment.id(), new PaymentVoidRequest("Wrong amount entered"));

        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).outstanding()));
        assertThrows(ResponseStatusException.class, () -> reservationService.checkOut(reservation),
                "outstanding balance must be zero for check-out");
        assertEquals("CHECKED_IN", statusOf(reservation), "rejected checkout changes nothing");

        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        reservationService.checkOut(reservation);

        assertEquals("CHECKED_OUT", statusOf(reservation), "checkout succeeds once the balance is genuinely corrected");
    }

    /** Confirms the DB rejects a VOIDED Charge with no void reason (CHECK constraint, not just app validation). */
    @Test
    void shouldRejectDbInsertOfVoidedChargeWithoutReason() {
        UUID stay = checkedInStay();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO charge (id, stay_id, type, description, amount, charged_at, status, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, 'MINIBAR', 'x', 1, now(), 'VOIDED', now(), ?, now(), ?)",
                UUID.randomUUID(), stay, user, user));
    }

    /** Confirms the DB rejects a VOIDED Payment with no void reason (CHECK constraint, not just app validation). */
    @Test
    void shouldRejectDbInsertOfVoidedPaymentWithoutReason() {
        UUID stay = checkedInStay();
        UUID reservation = reservationOf(stay);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO payment (id, stay_id, reservation_id, amount, currency, applied_amount, method, status, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 1, 'VND', 1, 'CASH', 'VOIDED', now(), ?, now(), ?)",
                UUID.randomUUID(), stay, reservation, user, user));
    }

    /** Runs two folio operations simultaneously and returns each outcome or the exception it threw. */
    private List<Object> race(java.util.concurrent.Callable<Object> first,
            java.util.concurrent.Callable<Object> second) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<Object> operation : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    authenticate();
                    barrier.await();
                    try {
                        return operation.call();
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            List<Object> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String statusOf(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
    }

    private UUID reservationOf(UUID stay) {
        return jdbc.queryForObject("SELECT reservation_id FROM stay WHERE id = ?", UUID.class, stay);
    }

    private UUID room(String number) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET status = 'AVAILABLE'",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    private UUID confirmed(UUID room) {
        UUID id = UUID.randomUUID();
        LocalDate checkOut = today.plusDays(2);
        BigDecimal total = new BigDecimal("4000000");
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "VD" + id.toString().substring(0, 12), guest, today, checkOut, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, today, checkOut, new BigDecimal("2000000"), total, user, user);
        return id;
    }
}
