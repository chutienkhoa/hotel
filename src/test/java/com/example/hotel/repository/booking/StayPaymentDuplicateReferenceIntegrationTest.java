package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Verifies the V1 Stay-level Payment duplicate-reference guard against real PostgreSQL.
 *
 * <p>For Payments of one Reservation, the tuple
 * {@code (payment method, trimmed non-blank reference, live status)} may be recorded only once, so a
 * manual retry or a double-submitted folio form cannot record the same real-world bank transfer or
 * card transaction twice. "Live" is the existing
 * {@link PaymentRepository#existsLiveWithReference} definition already used by the pre-check-in
 * prepayment guard: anything other than FAILED, REFUNDED or VOIDED. The method is part of the tuple,
 * a blank reference never collides, and a corrected (non-live) Payment releases its reference
 * immediately.</p>
 *
 * <p>This is not payment-gateway idempotency and there is no database unique index in V1: the check
 * runs inside the Stay {@code PESSIMISTIC_WRITE} lock that the folio boundaries already take, which
 * is what makes two concurrent requests unable to both pass it.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StayPaymentDuplicateReferenceIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String OK = "ok";
    private static final int RACE_ITERATIONS = 10;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PrepaymentService prepayments;

    @Autowired
    private StayBalanceService balances;

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

    /** Clears booking and financial data and creates the authenticated test actor and primary Guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log WHERE action IN ('CHECK_IN', 'RECORD_PAYMENT', 'RECORD_PREPAYMENT', "
                + "'APPLY_PREPAYMENT', 'REFUND_PAYMENT', 'VOID_PAYMENT')");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "dup-ref." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)",
                guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
        authenticate();
    }

    /**
     * Confirms the first record-paid with a non-blank method and reference is accepted and a second
     * identical live Payment is rejected, leaving exactly one Payment row and one settled amount.
     */
    @Test
    void shouldAcceptTheFirstReferenceAndRejectAnIdenticalSecondLivePayment() {
        UUID stay = checkedInStay();

        PaymentResponse first = payments.recordPaid(stay, transfer("500000", "FT123"));

        assertEquals("PAID", first.status());
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> payments.recordPaid(stay, transfer("500000", "FT123")));
        assertEquals(409, exception.getStatusCode().value());
        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay));
        assertEquals(0, new BigDecimal("500000").compareTo(balances.calculate(stay).totalPaidPayments()));
    }

    /**
     * Confirms the rejection is the duplicate guard and not the overpayment guard: it still applies
     * while the aggregate would stay far below Total Charges.
     */
    @Test
    void shouldRejectTheDuplicateWhileTotalChargesWouldStillCoverIt() {
        UUID stay = checkedInStay();
        payments.recordPaid(stay, transfer("1", "FT123"));

        assertThrows(ResponseStatusException.class, () -> payments.recordPaid(stay, transfer("1", "FT123")));

        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay));
        assertTrue(balances.calculate(stay).outstanding().signum() > 0, "the folio is nowhere near settled");
    }

    /** Confirms whitespace around an otherwise identical reference does not evade the guard. */
    @Test
    void shouldTreatSurroundingWhitespaceAsTheSameReference() {
        UUID stay = checkedInStay();
        payments.recordPaid(stay, transfer("100000", " FT123 "));

        assertThrows(ResponseStatusException.class, () -> payments.recordPaid(stay, transfer("100000", "FT123")));

        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay));
    }

    /**
     * Confirms the approved tuple rule: the same reference text under a DIFFERENT method is a
     * different real-world payment and is accepted, and the same tuple on a DIFFERENT Reservation is
     * likewise unaffected.
     */
    @Test
    void shouldAllowTheSameReferenceTextUnderADifferentMethodOrReservation() {
        UUID stay = checkedInStay();
        UUID otherStay = checkedInStay();
        payments.recordPaid(stay, transfer("100000", "FT123"));

        payments.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("100000"), PaymentCurrency.VND, null, PaymentMethod.CREDIT_CARD, "FT123"));
        payments.recordPaid(otherStay, transfer("100000", "FT123"));

        assertEquals(2, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", otherStay));
    }

    /** Confirms blank and absent references never collide, so several cash payments stay possible. */
    @Test
    void shouldAllowRepeatedBlankReferencePayments() {
        UUID stay = checkedInStay();

        payments.recordPaid(stay, cash("100000", null));
        payments.recordPaid(stay, cash("100000", "   "));
        payments.recordPaid(stay, cash("100000", null));

        assertEquals(3, count("SELECT COUNT(*) FROM payment WHERE stay_id = ?", stay));
        assertEquals(0, new BigDecimal("300000").compareTo(balances.calculate(stay).totalPaidPayments()));
    }

    /**
     * Confirms the guard follows the existing live-status semantics exactly: a VOIDED Payment (a
     * record entered in error) and a REFUNDED Payment (money handed back) both release their
     * reference immediately, so the corrected entry can carry the same real-world reference.
     */
    @Test
    void shouldReleaseTheReferenceOfAVoidedOrRefundedPayment() {
        UUID stay = checkedInStay();
        PaymentResponse wrong = payments.recordPaid(stay, transfer("100000", "FT-VOID"));
        payments.voidPayment(wrong.id(), new PaymentVoidRequest("wrong amount recorded"));

        PaymentResponse corrected = payments.recordPaid(stay, transfer("200000", "FT-VOID"));
        assertEquals("PAID", corrected.status());

        PaymentResponse refundable = payments.recordPaid(stay, transfer("100000", "FT-REFUND"));
        payments.refund(refundable.id(), new PaymentRefundRequest("guest overpaid at the desk"));

        assertEquals("PAID", payments.recordPaid(stay, transfer("100000", "FT-REFUND")).status());
        assertEquals(1, count(
                "SELECT COUNT(*) FROM payment WHERE stay_id = ? AND reference = 'FT-VOID' AND status = 'VOIDED'",
                stay));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM payment WHERE stay_id = ? AND reference = 'FT-REFUND'", stay));
    }

    /**
     * Confirms a PENDING Payment counts as live: the awaiting-confirmation creation boundary and the
     * record-paid shortcut share one guard, so the same reference cannot be entered through both.
     */
    @Test
    void shouldTreatAPendingPaymentAsLiveAcrossBothCreationBoundaries() {
        UUID stay = checkedInStay();
        PaymentResponse pending = payments.create(stay, transfer("100000", "FT-PENDING"));

        assertEquals("PENDING", pending.status());
        assertThrows(ResponseStatusException.class, () -> payments.recordPaid(stay, transfer("100000", "FT-PENDING")));
        assertThrows(ResponseStatusException.class, () -> payments.create(stay, transfer("100000", "FT-PENDING")));

        payments.markFailed(pending.id());
        assertEquals("PAID", payments.recordPaid(stay, transfer("100000", "FT-PENDING")).status(),
                "a FAILED Payment is not live, so its reference is available again");
    }

    /**
     * Confirms the guard is scoped to the Reservation, not to the Stay: a prepayment recorded before
     * check-in and a folio Payment recorded afterwards belong to the same Reservation, so the same
     * method and reference cannot be recorded through both.
     */
    @Test
    void shouldRejectAFolioPaymentDuplicatingAnAppliedPrepaymentReference() {
        UUID reservation = confirmedReservation();
        prepayments.record(reservation, transfer("100000", "FT-PRE"));
        reservations.checkIn(reservation);
        UUID stay = jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);

        assertThrows(ResponseStatusException.class, () -> payments.recordPaid(stay, transfer("100000", "FT-PRE")));

        assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", reservation));
    }

    /**
     * Confirms true concurrency: two simultaneous record-paid requests for the same tuple cannot both
     * commit, because the check runs inside the Stay lock the folio boundary already holds. Exactly
     * one live Payment exists afterwards.
     */
    @Test
    void shouldLetAtMostOneOfTwoConcurrentIdenticalRecordPaidRequestsSucceed() throws Exception {
        for (int iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
            setUp();
            UUID stay = checkedInStay();

            List<Object> results = race(
                    () -> payments.recordPaid(stay, transfer("100000", "FT-RACE")),
                    () -> payments.recordPaid(stay, transfer("100000", "FT-RACE")));

            assertEquals(1, results.stream().filter(OK::equals).count(), results.toString());
            assertEquals(1, count("SELECT COUNT(*) FROM payment WHERE stay_id = ? AND reference = 'FT-RACE' "
                    + "AND status NOT IN ('FAILED', 'REFUNDED', 'VOIDED')", stay));
            assertEquals(0, new BigDecimal("100000").compareTo(balances.calculate(stay).totalPaidPayments()));
        }
    }

    /** Runs two authenticated service calls from separate transactions at the same starting barrier. */
    private List<Object> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Runnable task : List.of(first, second)) {
                Callable<Object> call = () -> {
                    authenticate();
                    barrier.await();
                    try {
                        task.run();
                        return OK;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                };
                futures.add(pool.submit(call));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Installs the application CurrentUser principal in the calling thread. */
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    /** Creates a same-currency BANK_TRANSFER Payment request with the given reference. */
    private static PaymentCreateRequest transfer(String amount, String reference) {
        return new PaymentCreateRequest(
                new BigDecimal(amount), PaymentCurrency.VND, null, PaymentMethod.BANK_TRANSFER, reference);
    }

    /** Creates a same-currency CASH Payment request with the given reference. */
    private static PaymentCreateRequest cash(String amount, String reference) {
        return new PaymentCreateRequest(
                new BigDecimal(amount), PaymentCurrency.VND, null, PaymentMethod.CASH, reference);
    }

    /** Counts rows for an invariant query. */
    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /** Checks in a fresh Reservation through the real operation and returns its Stay identifier. */
    private UUID checkedInStay() {
        UUID reservation = confirmedReservation();
        reservations.checkIn(reservation);
        return jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservation);
    }

    /** Creates one active, available Room with configured capacity. */
    private UUID room() {
        UUID id = UUID.randomUUID();
        String number = "DR-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, ?, 'AVAILABLE', TRUE, now(), ?, now(), ?)",
                id, number, SINGLE_ROOM_TYPE_ID, user, user);
        return id;
    }

    /** Creates one CONFIRMED Reservation arriving today for two nights, with one priced Room line. */
    private UUID confirmedReservation() {
        UUID room = room();
        UUID id = UUID.randomUUID();
        LocalDate in = today;
        LocalDate out = today.plusDays(2);
        BigDecimal nightlyRate = new BigDecimal("1000000");
        long nights = ChronoUnit.DAYS.between(in, out);
        BigDecimal total = nightlyRate.multiply(BigDecimal.valueOf(nights));
        jdbc.update("INSERT INTO reservation (id, reservation_number, guest_id, source, status, reserved_at, "
                        + "check_in_date, check_out_date, currency, total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'DIRECT', 'CONFIRMED', now(), ?, ?, 'VND', ?, now(), ?, now(), ?)",
                id, "DR" + id.toString().substring(0, 12), guest, in, out, total, user, user);
        jdbc.update("INSERT INTO reservation_room (id, reservation_id, room_id, check_in_date, check_out_date, nightly_rate, "
                        + "total_amount, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, now(), ?)",
                UUID.randomUUID(), id, room, in, out, nightlyRate, total, user, user);
        return id;
    }
}
