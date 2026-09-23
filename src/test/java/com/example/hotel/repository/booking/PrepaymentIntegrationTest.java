package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PrepaymentService;
import org.springframework.web.server.ResponseStatusException;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
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
 * Verifies Prepayment / Advance Payment V1 against PostgreSQL: recording, cap, duplicate guard, whole refund, cancel and
 * no-show guards, application to the Stay at check-in (the SAME rows), the folio afterwards, revenue independence, the
 * Excel payments query, and the pre-check-in lifecycle races.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PrepaymentIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PrepaymentService prepayments;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StayExtensionService extensionService;

    @Autowired
    private ChargeService chargeService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private StayBalanceService balances;

    @Autowired
    private PaymentRepository paymentRepository;

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
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHANGE_ROOM', 'CONFIRM', 'EXTEND_STAY', 'RECORD_PREPAYMENT', 'APPLY_PREPAYMENT', 'REFUND_PAYMENT', 'CHECK_IN', 'CANCEL', 'NO_SHOW')");
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
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "pre." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    private static PaymentCreateRequest vnd(String amount, String reference) {
        return new PaymentCreateRequest(new BigDecimal(amount), PaymentCurrency.VND, null, PaymentMethod.BANK_TRANSFER, reference);
    }

    /** Creates a CONFIRMED reservation of 4,000,000 VND (2 nights x 2,000,000) starting today. */
    private UUID fourMillion() {
        UUID room = room("PP-" + UUID.randomUUID().toString().substring(0, 6), "AVAILABLE");
        return confirmed(today, today.plusDays(2), new Line(room, "2000000"));
    }

    /**
     * Creates a CONFIRMED reservation of 4,000,000 VND (2 nights x 2,000,000) with a check-in date already in
     * the past, so it is eligible for No-show under the temporal guard (late check-in is still allowed).
     */
    private UUID fourMillionOverdue() {
        UUID room = room("PO-" + UUID.randomUUID().toString().substring(0, 6), "AVAILABLE");
        return confirmed(today.minusDays(1), today.plusDays(1), new Line(room, "2000000"));
    }

    private static CancelReservationRequest cancelRequest() {
        return new CancelReservationRequest(CancellationReasonCode.GUEST_REQUEST, null);
    }

    private static NoShowReservationRequest noShowRequest() {
        return new NoShowReservationRequest("Guest did not arrive and could not be contacted.");
    }

    /** Confirms a VND prepayment is PAID with no stay, adds no revenue, and multiple prepayments accumulate. */
    @Test
    void shouldRecordMultiplePaidPrepaymentsWithoutRevenue() {
        UUID reservation = fourMillion();
        YearMonth month = YearMonth.from(today);
        var before = financialReport.report(month);

        prepayments.record(reservation, vnd("500000", "T1"));
        prepayments.record(reservation, vnd("500000", "T2"));
        prepayments.record(reservation, vnd("1000000", null));

        assertEquals(3, count("SELECT COUNT(*) FROM payment WHERE reservation_id = ? AND stay_id IS NULL AND status = 'PAID'", reservation));
        assertEquals(0, new BigDecimal("2000000").compareTo(prepayments.summary(reservation).activeTotal()));
        assertEquals(0, new BigDecimal("2000000").compareTo(prepayments.summary(reservation).remaining()));
        var after = financialReport.report(month);
        assertEquals(0, before.roomRevenue().compareTo(after.roomRevenue()));
        assertEquals(0, before.additionalRevenue().compareTo(after.additionalRevenue()));
        assertEquals(0, before.netProfit().compareTo(after.netProfit()));
        assertEquals(0, count("SELECT COUNT(*) FROM charge"));
        assertEquals(0, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IS NOT NULL"));
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log WHERE action = 'RECORD_PREPAYMENT' AND entity_id = ?", reservation));
    }

    /** Confirms a USD prepayment on a VND reservation is converted with the existing FX rules. */
    @Test
    void shouldConvertAUsdPrepaymentToTheReservationCurrency() {
        UUID reservation = fourMillion();

        prepayments.record(reservation, new PaymentCreateRequest(
                new BigDecimal("100"), PaymentCurrency.USD, new BigDecimal("25000"), PaymentMethod.CASH, null));

        assertEquals(0, new BigDecimal("2500000").compareTo(jdbc.queryForObject(
                "SELECT applied_amount FROM payment WHERE reservation_id = ?", BigDecimal.class, reservation)));
        assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject(
                "SELECT amount FROM payment WHERE reservation_id = ?", BigDecimal.class, reservation)));
    }

    /** Confirms the cap: exact full prepayment is allowed, overpayment rejected, refunded amounts do not count. */
    @Test
    void shouldEnforceTheReservationTotalCap() {
        UUID reservation = fourMillion();
        prepayments.record(reservation, vnd("3000000", "A"));

        assertThrows(ResponseStatusException.class, () -> prepayments.record(reservation, vnd("1000001", "B")));
        prepayments.record(reservation, vnd("1000000", "C"));
        assertThrows(ResponseStatusException.class, () -> prepayments.record(reservation, vnd("1", "D")));
        UUID first = jdbc.queryForObject("SELECT id FROM payment WHERE reference = 'A'", UUID.class);
        prepayments.refund(reservation, first, new PaymentRefundRequest("changed mind"));
        prepayments.record(reservation, vnd("3000000", "E"));
        assertEquals(0, new BigDecimal("4000000").compareTo(prepayments.summary(reservation).activeTotal()));
        assertEquals(0, new BigDecimal("3000000").compareTo(prepayments.summary(reservation).refundedTotal()));
    }

    /** Confirms only a CONFIRMED reservation without a Stay accepts a prepayment. */
    @Test
    void shouldRejectPrepaymentForOtherStatesAndExistingStay() {
        for (String status : List.of("DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT")) {
            UUID room = room("PS-" + status, "AVAILABLE");
            UUID reservation = reservation(status, today, today.plusDays(2), new Line(room, "2000000"));
            assertThrows(ResponseStatusException.class, () -> prepayments.record(reservation, vnd("1", "X")), status);
        }
        UUID room = room("PS-STAY", "OCCUPIED");
        UUID withStay = confirmed(today, today.plusDays(2), new Line(room, "2000000"));
        seedStay(withStay);
        assertThrows(ResponseStatusException.class, () -> prepayments.record(withStay, vnd("1", "X")));
        assertEquals(0, count("SELECT COUNT(*) FROM payment"));
    }

    /** Confirms the duplicate guard: same method and reference on one reservation only, ignoring FAILED/REFUNDED. */
    @Test
    void shouldGuardAgainstDuplicateReferences() {
        UUID reservation = fourMillion();
        UUID other = fourMillion();
        prepayments.record(reservation, vnd("100", " REF-1 "));

        assertThrows(ResponseStatusException.class, () -> prepayments.record(reservation, vnd("100", "REF-1")));
        prepayments.record(reservation, vnd("100", "REF-2"));
        prepayments.record(other, vnd("100", "REF-1"));
        prepayments.record(reservation, new PaymentCreateRequest(new BigDecimal("100"), PaymentCurrency.VND, null, PaymentMethod.CASH, "REF-1"));
        prepayments.record(reservation, vnd("100", null));
        prepayments.record(reservation, vnd("100", "  "));

        UUID first = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ? AND reference = 'REF-1' AND method = 'BANK_TRANSFER'", UUID.class, reservation);
        prepayments.refund(reservation, first, new PaymentRefundRequest("x"));
        prepayments.record(reservation, vnd("100", "REF-1"));
    }

    /** Confirms whole refund keeps the reservation CONFIRMED, cannot repeat, and cannot touch an applied payment. */
    @Test
    void shouldRefundWholeBeforeCheckInOnly() {
        UUID reservation = fourMillion();
        prepayments.record(reservation, vnd("1000000", "R1"));
        UUID payment = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", UUID.class, reservation);
        assertThrows(ResponseStatusException.class, () -> prepayments.refund(reservation, payment, new PaymentRefundRequest(" ")));

        prepayments.refund(reservation, payment, new PaymentRefundRequest("guest cancelled the transfer"));

        assertEquals("CONFIRMED", statusOf(reservation));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, payment));
        assertEquals(0, prepayments.summary(reservation).activeTotal().signum());
        assertThrows(ResponseStatusException.class, () -> prepayments.refund(reservation, payment, new PaymentRefundRequest("again")));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REFUND_PAYMENT' AND entity_id = ?", reservation));

        UUID kept = fourMillion();
        prepayments.record(kept, vnd("1000000", "R2"));
        UUID keptPayment = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", UUID.class, kept);
        reservationService.checkIn(kept);
        assertThrows(ResponseStatusException.class, () -> prepayments.refund(kept, keptPayment, new PaymentRefundRequest("late")));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, keptPayment));
    }

    /** Confirms cancel and no-show are blocked by active prepayments, work after refund, and never move money or revenue. */
    @Test
    void shouldBlockCancelAndNoShowUntilPrepaymentsAreRefunded() {
        UUID toCancel = fourMillion();
        UUID toNoShow = fourMillionOverdue();
        prepayments.record(toCancel, vnd("1000000", "C1"));
        prepayments.record(toNoShow, vnd("1000000", "N1"));

        assertThrows(ResponseStatusException.class, () -> reservationService.cancel(toCancel, cancelRequest()));
        assertThrows(ResponseStatusException.class, () -> reservationService.noShow(toNoShow, noShowRequest()));
        assertEquals("CONFIRMED", statusOf(toCancel));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE reservation_id = ?", String.class, toCancel));

        for (UUID reservation : List.of(toCancel, toNoShow)) {
            UUID payment = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", UUID.class, reservation);
            prepayments.refund(reservation, payment, new PaymentRefundRequest("refund first"));
        }
        reservationService.cancel(toCancel, cancelRequest());
        reservationService.noShow(toNoShow, noShowRequest());

        assertEquals("CANCELLED", statusOf(toCancel));
        assertEquals("NO_SHOW", statusOf(toNoShow));
        assertEquals(0, count("SELECT COUNT(*) FROM charge"));
        assertEquals(0, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IS NOT NULL"));
        assertEquals(2, count("SELECT COUNT(*) FROM payment WHERE status = 'REFUNDED'"));
        assertEquals("GUEST_REQUEST", jdbc.queryForObject(
                "SELECT cancellation_reason_code FROM reservation WHERE id = ?", String.class, toCancel));
        assertEquals("Guest did not arrive and could not be contacted.", jdbc.queryForObject(
                "SELECT no_show_reason FROM reservation WHERE id = ?", String.class, toNoShow));
        String cancelOldValue = jdbc.queryForObject(
                "SELECT old_value FROM audit_log WHERE action = 'CANCEL' AND entity_id = ?", String.class, toCancel);
        String cancelNewValue = jdbc.queryForObject(
                "SELECT new_value FROM audit_log WHERE action = 'CANCEL' AND entity_id = ?", String.class, toCancel);
        assertEquals("CONFIRMED", cancelOldValue);
        assertEquals("CANCELLED", cancelNewValue);
        String noShowNewValue = jdbc.queryForObject(
                "SELECT new_value FROM audit_log WHERE action = 'NO_SHOW' AND entity_id = ?", String.class, toNoShow);
        assertEquals("NO_SHOW", noShowNewValue);
        assertFalse(noShowNewValue.contains("did not arrive"), "AuditLog must not duplicate the raw no-show reason text");
    }

    /** Confirms check-in attaches the SAME rows (no copies, unchanged snapshot), skips refunded ones, and the folio adds up. */
    @Test
    void shouldApplyTheSamePrepaymentRowsAtCheckIn() {
        UUID reservation = fourMillion();
        prepayments.record(reservation, vnd("500000", "P1"));
        prepayments.record(reservation, vnd("500000", "P2"));
        prepayments.record(reservation, vnd("700000", "P3"));
        UUID refunded = jdbc.queryForObject("SELECT id FROM payment WHERE reference = 'P3'", UUID.class);
        prepayments.refund(reservation, refunded, new PaymentRefundRequest("x"));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM payment WHERE reference IN ('P1','P2') ORDER BY reference", UUID.class);
        BigDecimal amountBefore = jdbc.queryForObject("SELECT SUM(amount) FROM payment WHERE id IN (?, ?)", BigDecimal.class, ids.get(0), ids.get(1));

        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);

        assertEquals(3, count("SELECT COUNT(*) FROM payment"), "no copied Payment rows");
        assertEquals(2, count("SELECT COUNT(*) FROM payment WHERE stay_id = ? AND reservation_id = ? AND status = 'PAID' AND id IN (?, ?)", stay, reservation, ids.get(0), ids.get(1)));
        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE id = ? AND stay_id IS NULL AND status = 'REFUNDED'", refunded), "refunded is not applied");
        assertEquals(0, amountBefore.compareTo(jdbc.queryForObject("SELECT SUM(amount) FROM payment WHERE stay_id = ?", BigDecimal.class, stay)));
        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).totalCharges()));
        assertEquals(0, new BigDecimal("1000000").compareTo(balances.calculate(stay).totalPaidPayments()));
        assertEquals(0, new BigDecimal("3000000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'APPLY_PREPAYMENT' AND entity_id = ?", reservation));
    }

    /** Confirms full prepayment settles the folio, and extension and a service charge then add to outstanding normally. */
    @Test
    void shouldKeepTheNormalFolioAfterFullPrepayment() {
        UUID reservation = fourMillion();
        prepayments.record(reservation, vnd("4000000", "FULL"));
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);
        assertEquals(0, balances.calculate(stay).outstanding().signum());

        extensionService.extend(reservation, new StayExtensionRequest(today.plusDays(2), today.plusDays(3)));
        assertEquals(0, new BigDecimal("2000000").compareTo(balances.calculate(stay).outstanding()));

        chargeService.create(stay, new ChargeCreateRequest(ChargeType.MINIBAR, "cola", null, null, new BigDecimal("100000")));
        assertEquals(0, new BigDecimal("2100000").compareTo(balances.calculate(stay).outstanding()));
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id IS NOT NULL"));
    }

    /** Confirms post-check-in payments still work and belong to the reservation. */
    @Test
    void shouldKeepPostCheckInPaymentsWorking() {
        UUID reservation = fourMillion();
        reservationService.checkIn(reservation);
        UUID stay = stayOf(reservation);

        paymentService.recordPaid(stay, vnd("1000000", "POST"));
        var pending = paymentService.create(stay, vnd("500000", "PEND"));
        paymentService.markPaid(pending.id());

        assertEquals(2, count("SELECT COUNT(*) FROM payment WHERE stay_id = ? AND reservation_id = ?", stay, reservation));
        assertEquals(0, new BigDecimal("2500000").compareTo(balances.calculate(stay).outstanding()));
    }

    /** Confirms the Payments export includes pre-check-in payments (paid or refunded) exactly once, before and after check-in. */
    @Test
    void shouldListPrepaymentsInThePaymentsExportOnce() {
        UUID reservation = fourMillion();
        prepayments.record(reservation, vnd("1000000", "X1"));
        prepayments.record(reservation, vnd("100000", "X2"));
        UUID second = jdbc.queryForObject("SELECT id FROM payment WHERE reference = 'X2'", UUID.class);
        prepayments.refund(reservation, second, new PaymentRefundRequest("x"));
        var from = today.atStartOfDay(ZONE).toInstant();
        var to = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        var statuses = List.of(PaymentStatus.PAID, PaymentStatus.REFUNDED);

        assertEquals(2, paymentRepository.findExportRowsPaidWithin(statuses, from, to).size());

        reservationService.checkIn(reservation);

        assertEquals(2, paymentRepository.findExportRowsPaidWithin(statuses, from, to).size(), "no duplicate after application");
    }

    /** Confirms a prepayment racing check-in is either applied or rejected, never left unattached after check-in. */
    @Test
    void shouldSerializePrepaymentAndCheckIn() throws Exception {
        for (int i = 0; i < 12; i++) {
            setUp();
            UUID reservation = fourMillion();
            List<Object> results = race(() -> prepayments.record(reservation, vnd("100000", "RACE")),
                    () -> reservationService.checkIn(reservation));

            assertTrue(results.get(1) instanceof String, results.toString());
            assertEquals(0, count("SELECT COUNT(*) FROM payment WHERE reservation_id = ? AND stay_id IS NULL AND status = 'PAID'", reservation),
                    "never an unattached prepayment behind a completed check-in");
            boolean recorded = !(results.get(0) instanceof RuntimeException);
            assertEquals(recorded ? 1 : 0, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stayOf(reservation)));
        }
    }

    /** Confirms a refund racing check-in ends either refunded-and-unapplied or applied-and-PAID. */
    @Test
    void shouldSerializeRefundAndCheckIn() throws Exception {
        for (int i = 0; i < 12; i++) {
            setUp();
            UUID reservation = fourMillion();
            prepayments.record(reservation, vnd("100000", "RF"));
            UUID payment = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", UUID.class, reservation);

            List<Object> results = race(() -> prepayments.refund(reservation, payment, new PaymentRefundRequest("x")),
                    () -> reservationService.checkIn(reservation));

            assertTrue(results.get(1) instanceof String, results.toString());
            String status = jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, payment);
            boolean attached = jdbc.queryForObject("SELECT stay_id IS NOT NULL FROM payment WHERE id = ?", Boolean.class, payment);
            assertTrue(("REFUNDED".equals(status) && !attached) || ("PAID".equals(status) && attached), status + attached);
        }
    }

    /** Confirms check-in versus cancel and versus no-show: exactly one lifecycle path wins. */
    @Test
    void shouldSerializeCheckInWithCancelAndNoShow() throws Exception {
        for (int i = 0; i < 12; i++) {
            setUp();
            // An overdue check-in date is used for both branches: Cancel is unaffected by it, late check-in is
            // still allowed, and it keeps No-show eligible under the temporal guard so the race is exercised.
            UUID reservation = fourMillionOverdue();
            boolean cancel = i % 2 == 0;
            List<Object> results = race(() -> reservationService.checkIn(reservation),
                    () -> { if (cancel) { reservationService.cancel(reservation, cancelRequest()); } else { reservationService.noShow(reservation, noShowRequest()); } });

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            String status = statusOf(reservation);
            int stays = count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservation);
            assertTrue(("CHECKED_IN".equals(status) && stays == 1) || (!"CHECKED_IN".equals(status) && stays == 0), status + stays);
        }
    }

    /** Confirms a prepayment racing cancel or no-show never leaves an active prepayment behind a closed reservation. */
    @Test
    void shouldSerializePrepaymentWithCancelAndNoShow() throws Exception {
        for (int i = 0; i < 12; i++) {
            setUp();
            UUID reservation = fourMillionOverdue();
            boolean cancel = i % 2 == 0;
            List<Object> results = race(() -> prepayments.record(reservation, vnd("100000", "CN")),
                    () -> { if (cancel) { reservationService.cancel(reservation, cancelRequest()); } else { reservationService.noShow(reservation, noShowRequest()); } });

            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            int active = count("SELECT COUNT(*) FROM payment WHERE reservation_id = ? AND stay_id IS NULL AND status = 'PAID'", reservation);
            String status = statusOf(reservation);
            assertTrue(("CONFIRMED".equals(status) && active == 1) || (!"CONFIRMED".equals(status) && active == 0), status + active);
        }
    }

    /** Confirms concurrent prepayments cannot exceed the total, and concurrent identical references cannot both succeed. */
    @Test
    void shouldSerializeAmountLimitAndDuplicateReference() throws Exception {
        for (int i = 0; i < 10; i++) {
            setUp();
            UUID room = room("PL-A", "AVAILABLE");
            UUID reservation = confirmed(today, today.plusDays(1), new Line(room, "1000000"));

            List<Object> results = race(() -> prepayments.record(reservation, vnd("700000", "L1")),
                    () -> prepayments.record(reservation, vnd("700000", "L2")));
            assertEquals(1, results.stream().filter(r -> !(r instanceof RuntimeException)).count(), results.toString());
            assertEquals(0, new BigDecimal("700000").compareTo(prepayments.summary(reservation).activeTotal()));

            setUp();
            UUID second = confirmed(today, today.plusDays(1), new Line(room("PL-B", "AVAILABLE"), "1000000"));
            List<Object> dup = race(() -> prepayments.record(second, vnd("100", "SAME")),
                    () -> prepayments.record(second, vnd("100", "SAME")));
            assertEquals(1, dup.stream().filter(r -> !(r instanceof RuntimeException)).count(), dup.toString());
            assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", second));
        }
    }

    private String statusOf(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
    }

    private UUID seedStay(UUID reservation) {
        UUID stay = UUID.randomUUID();
        jdbc.update("INSERT INTO stay (id, reservation_id, status, actual_check_in_at, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'CHECKED_IN', now(), now(), ?, now(), ?)", stay, reservation, user, user);
        return stay;
    }

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
                id, "PP" + id.toString().substring(0, 12), guest, status, in, out, total, user, user);
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
