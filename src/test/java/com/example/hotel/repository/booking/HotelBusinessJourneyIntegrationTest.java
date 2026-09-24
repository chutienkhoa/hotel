package com.example.hotel.repository.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.ChargeVoidRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.RoomHistoryLineResponse;
import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.RoomChangeService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
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
 * E2E Hardening C: proves that the major V1 capabilities compose correctly as real hotel journeys against
 * PostgreSQL, rather than only in isolation. Each journey drives the ACTUAL production service boundaries
 * (creation, confirmation, check-in, Room Change, Stay Extension, Charge, Payment, check-out, Housekeeping) in
 * the same order a real front-desk operation would use them; direct JDBC is used only for bootstrap data
 * (the acting user, Guests, Rooms) that no journey is actually about, exactly as every other PostgreSQL
 * integration test in this package already does.
 *
 * <ul>
 *   <li>J1 {@link #otaReservationLifecycleSurvivesAcrossAllThreeSources()} -- an OTA booking's source and
 *       reference survive create/confirm/check-in/payment/check-out and remain correctly reported, and its
 *       external identity (Hardening B2) still blocks a duplicate after the Reservation reaches CHECKED_OUT.</li>
 *   <li>J2 {@link #folioCorrectionLifecycleReachesABalancedCheckout()} -- a genuine payment mistake is
 *       protected by the Hardening B1 negative-balance guard, corrected through the approved Payment-void-first
 *       order, and the folio still reaches exactly zero and checks out.</li>
 *   <li>J3 {@link #noShowLifecycleReleasesInventoryAfterThePrepaymentGuard()} -- a past-dated CONFIRMED booking
 *       is blocked from No-show by an active prepayment, then released once refunded, marked No-show, and its
 *       room/date inventory becomes bookable again for a genuinely new Reservation.</li>
 *   <li>J4 {@link #fullMultiRoomHotelJourneyEndsWithTheRoomReusedByANewGuest()} -- a two-room Stay goes through
 *       Room Change, Stay Extension, a guest-service Charge, Payment and check-out, then Housekeeping, and the
 *       rooms are proven genuinely reusable by a second, independent Reservation.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HotelBusinessJourneyIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationQueryService reservationQueryService;

    @Autowired
    private ChargeService chargeService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PrepaymentService prepaymentService;

    @Autowired
    private StayExtensionService extensionService;

    @Autowired
    private RoomChangeService roomChangeService;

    @Autowired
    private RoomAvailabilityService availability;

    @Autowired
    private RoomService roomService;

    @Autowired
    private StayBalanceService balances;

    @Autowired
    private StayRoomAssignmentQueryService stayRoomAssignmentQuery;

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

    /** Clears every booking-related table and creates the acting user and a default guest. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM additional_revenue WHERE charge_id IS NOT NULL");
        jdbc.update("DELETE FROM stay_extension_room");
        jdbc.update("DELETE FROM stay_extension");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM stay_room_assignment");
        jdbc.update("DELETE FROM stay");
        jdbc.update("DELETE FROM reservation_guest");
        jdbc.update("DELETE FROM reservation_room");
        jdbc.update("DELETE FROM reservation");
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "journey." + user);
        guest = newGuest("J");
        today = LocalDate.now(clock);
        authenticate();
    }

    // ================================================================================================
    // J1 -- OTA Reservation Lifecycle
    // ================================================================================================

    /**
     * J1: proves each of the three supported OTA sources (AGODA, BOOKING_COM, AIRBNB) can travel through the
     * complete hotel lifecycle -- create, confirm, check-in, settle, check-out -- through the real production
     * services, without losing its source/reference identity, and that the identity is still reported correctly
     * and still permanently unique (Hardening B2) after the Reservation reaches its terminal CHECKED_OUT state.
     */
    @Test
    void otaReservationLifecycleSurvivesAcrossAllThreeSources() {
        for (BookingSource source : List.of(BookingSource.AGODA, BookingSource.BOOKING_COM, BookingSource.AIRBNB)) {
            String reference = "OTA-" + source.name() + "-001";
            UUID otaGuest = newGuest(source.name());
            UUID room = room("J1-" + source.name(), "AVAILABLE");
            // Check-in happens today (immediately, in the same test run), so the arrival date must be today:
            // an arrival in the future would correctly trip the approved EARLY check-in guard.
            LocalDate checkIn = today;
            LocalDate checkOut = today.plusDays(2);

            // 1-2. Create through the real production entry point: a genuine, non-DIRECT booking reference.
            Response created = reservationService.create(new CreateRequest(
                    otaGuest, checkIn, checkOut, 1, 0, source, reference, "VND", null,
                    List.of(new RoomRequest(room, new BigDecimal("1500000"))), List.of()));
            UUID reservationId = created.id();

            // 3-4. Confirm and verify the source/reference are preserved by the real read boundary.
            reservationService.confirm(reservationId);
            ReservationDetailResponse afterConfirm = reservationQueryService.findById(reservationId);
            assertEquals("CONFIRMED", afterConfirm.status());
            assertEquals(source, afterConfirm.source());
            assertEquals(reference, afterConfirm.otaBookingReference());

            // 5-6. Check-in through the real workflow: Stay created, Reservation CHECKED_IN, actual room
            // assignment opened, and the automatic ROOM Charge matches the immutable price snapshot.
            reservationService.checkIn(reservationId);
            UUID stay = stayOf(reservationId);
            assertEquals("CHECKED_IN", statusOf(reservationId));
            assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT status FROM stay WHERE id = ?", String.class, stay));
            List<CurrentRoomResponse> currentRooms = stayRoomAssignmentQuery.findCurrentRooms(reservationId);
            assertEquals(1, currentRooms.size());
            assertEquals(room, currentRooms.get(0).roomId());
            assertEquals(1, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
            assertEquals(0, new BigDecimal("3000000").compareTo(balances.calculate(stay).totalCharges()),
                    "2 nights x 1,500,000, exactly the ReservationRoom price snapshot");

            // 7-8. Record payment through the real Payment workflow; Outstanding reaches exactly zero.
            paymentService.recordPaid(stay, new PaymentCreateRequest(
                    new BigDecimal("3000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
            assertEquals(0, balances.calculate(stay).outstanding().signum());

            // 9-10. Check out through the real production workflow.
            reservationService.checkOut(reservationId);
            assertEquals("CHECKED_OUT", statusOf(reservationId));
            assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM stay WHERE id = ?", String.class, stay));
            assertNotNull(jdbc.queryForObject("SELECT actual_check_out_at FROM stay WHERE id = ?",
                    java.sql.Timestamp.class, stay));
            assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room),
                    "the approved post-checkout Room lifecycle: OCCUPIED -> DIRTY");

            // 11. Source identity is unchanged after the full lifecycle, including the terminal state.
            ReservationDetailResponse afterCheckOut = reservationQueryService.findById(reservationId);
            assertEquals(source, afterCheckOut.source());
            assertEquals(reference, afterCheckOut.otaBookingReference());

            // 12. The reservation is represented under its correct source in the existing reporting/query
            // path used by both the REST and MVC reservation lists (ReservationQueryService.findPage with a
            // source filter) -- not a query invented for this test.
            ReservationSearchCriteria criteria = new ReservationSearchCriteria();
            criteria.setSource(source);
            criteria.setOtaBookingReference(reference);
            Page<ReservationSummaryResponse> page = reservationQueryService.findPage(criteria, 0);
            assertEquals(1, page.getTotalElements());
            ReservationSummaryResponse row = page.getContent().get(0);
            assertEquals(reservationId, row.id());
            assertEquals(source, row.source());
            assertEquals(reference, row.otaBookingReference());
            assertEquals("CHECKED_OUT", row.status());

            // Also prove B2 persistence through the complete lifecycle (not a re-run of every B2 case, just
            // that the identity survives all the way to and past CHECKED_OUT): the SAME source+reference can
            // never be claimed by a new Reservation now.
            ResponseStatusException duplicate = assertThrows(ResponseStatusException.class, () -> reservationService.create(
                    new CreateRequest(otaGuest, today.plusDays(20), today.plusDays(22), 1, 0, source, reference,
                            "VND", null, List.of(new RoomRequest(room("J1-DUP-" + source.name(), "AVAILABLE"),
                                    new BigDecimal("1000000"))), List.of())));
            assertEquals(409, duplicate.getStatusCode().value());
        }
    }

    // ================================================================================================
    // J2 -- Folio Correction Lifecycle
    // ================================================================================================

    /**
     * J2: a genuine folio mistake -- a Payment recorded for the wrong amount, discovered only after a
     * guest-service Charge is disputed -- is protected by the Hardening B1 negative-balance guard, corrected
     * through the approved order (void the erroneous Payment BEFORE voiding the eligible Charge), and the folio
     * still reaches exactly zero Outstanding and checks out. This proves the sequence end to end, not each rule
     * in isolation (those are already covered by ChargeServiceTest / ChargePaymentVoidIntegrationTest).
     */
    @Test
    void folioCorrectionLifecycleReachesABalancedCheckout() {
        UUID room = room("J2-A", "AVAILABLE");
        LocalDate checkIn = today;
        LocalDate checkOut = today.plusDays(2);

        // 1. Reservation -> Confirm -> Check-in through the real production workflow.
        Response created = reservationService.create(new CreateRequest(
                guest, checkIn, checkOut, 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("2000000"))), List.of()));
        UUID reservationId = created.id();
        reservationService.confirm(reservationId);
        reservationService.checkIn(reservationId);
        UUID stay = stayOf(reservationId);
        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).totalCharges()),
                "automatic ROOM charge: 2 nights x 2,000,000");

        // 2. A legitimate guest-service Charge through the current production rules (links AdditionalRevenue).
        ChargeResponse minibar = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.MINIBAR, "Minibar snacks", null, null, new BigDecimal("300000")));
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id = ? AND status = 'RECORDED'",
                minibar.id()));
        assertEquals(0, new BigDecimal("4300000").compareTo(balances.calculate(stay).totalCharges()));

        // 3. A PAID Payment -- entered for the WRONG amount by mistake (a duplicate/typo of the true total),
        // which is exactly what makes the guard below meaningful: the folio looks fully settled.
        PaymentResponse mistakenPayment = paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4300000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "the folio looks settled");

        // 4-5. Attempt to void the minibar Charge: doing so would make Outstanding = 4,300,000 - 300,000 -
        // 4,300,000 = -300,000. The approved B1 guard rejects it, and NOTHING changes.
        ResponseStatusException blockedVoid = assertThrows(ResponseStatusException.class, () -> chargeService.voidCharge(
                minibar.id(), new ChargeVoidRequest("Guest disputed the minibar charge")));
        assertEquals(409, blockedVoid.getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM charge WHERE id = ?", String.class, minibar.id()),
                "the blocked void leaves the Charge ACTIVE");
        assertEquals("RECORDED", jdbc.queryForObject(
                "SELECT status FROM additional_revenue WHERE charge_id = ?", String.class, minibar.id()),
                "the linked Additional Revenue is untouched by the blocked void");
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, mistakenPayment.id()),
                "the mistaken Payment is untouched by the blocked Charge void");
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "the balance is unchanged by the rejection");
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'VOID_CHARGE' AND entity_id = ?", reservationId),
                "a rejected void never produces a successful VOID_CHARGE audit event");

        // 6. Correct the ROOT mistake first, through the approved Payment correction path: the erroneous
        // Payment was never actually a refund (nothing was handed back to the guest) -- it was simply wrong --
        // so VOID (not REFUND, and not an invented partial-refund/OVERPAID mechanism) is the approved path.
        paymentService.voidPayment(mistakenPayment.id(), new PaymentVoidRequest("Payment amount entered by mistake"));
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, mistakenPayment.id()));
        assertEquals(0, new BigDecimal("4300000").compareTo(balances.calculate(stay).outstanding()),
                "voiding the erroneous Payment restores the true, still-unpaid Outstanding");

        // 7-8. NOW the same Charge void is eligible: Outstanding would become 4,300,000 - 300,000 = 4,000,000,
        // never negative. The identical operation that was rejected above now succeeds.
        chargeService.voidCharge(minibar.id(), new ChargeVoidRequest("Guest disputed the minibar charge"));
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM charge WHERE id = ?", String.class, minibar.id()));
        assertEquals("VOIDED", jdbc.queryForObject(
                "SELECT status FROM additional_revenue WHERE charge_id = ?", String.class, minibar.id()),
                "the linked Additional Revenue is voided atomically with the Charge");
        assertEquals(0, new BigDecimal("4000000").compareTo(balances.calculate(stay).outstanding()));
        assertTrue(balances.calculate(stay).outstanding().signum() >= 0, "the corrected folio is never overpaid");

        // 9-10. Record the correct payment: exactly the remaining 4,000,000 (the ROOM charge alone).
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                new BigDecimal("4000000"), PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum());

        // 11-12. Check out succeeds on the balanced, corrected folio.
        reservationService.checkOut(reservationId);
        assertEquals("CHECKED_OUT", statusOf(reservationId));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM stay WHERE id = ?", String.class, stay));
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, room));
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "the folio remains balanced after checkout");
    }

    // ================================================================================================
    // J3 -- No-show Lifecycle
    // ================================================================================================

    /**
     * J3: a CONFIRMED Reservation whose planned arrival has already passed is blocked from No-show while an
     * active PAID prepayment exists, becomes eligible once that prepayment is refunded through the approved
     * pre-check-in workflow, and -- once marked No-show -- releases its room/date inventory for a genuinely new
     * Reservation. Also proves the temporal boundary through the real production guard: a same-day or future
     * arrival can never be marked No-show.
     */
    @Test
    void noShowLifecycleReleasesInventoryAfterThePrepaymentGuard() {
        UUID room = room("J3-A", "AVAILABLE");
        // 1-2. A CONFIRMED booking whose check-in date has already passed relative to hotelToday (the injected
        // Clock, never the wall clock), so it is eligible for the No-show temporal guard.
        LocalDate checkIn = today.minusDays(2);
        LocalDate checkOut = today.minusDays(1);
        Response created = reservationService.create(new CreateRequest(
                guest, checkIn, checkOut, 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of()));
        UUID reservationId = created.id();
        reservationService.confirm(reservationId);
        assertEquals("CONFIRMED", statusOf(reservationId));

        // 4. The room is reserved for its exact dates under the current lifecycle-aware availability rule
        // (CONFIRMED blocks via the ReservationRoom snapshot): a conflicting Confirm on the same room/dates
        // must fail, proving the inventory is genuinely held.
        assertTrue(availability.hasInventoryConflict(room, checkIn, checkOut));
        Response conflictingDraft = reservationService.create(new CreateRequest(
                guest, checkIn, checkOut, 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of()));
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> reservationService.confirm(conflictingDraft.id()));
        assertEquals(409, conflict.getStatusCode().value());

        // 5. An active PAID prepayment through the real pre-check-in workflow.
        PaymentResponse prepayment = prepaymentService.record(reservationId, new PaymentCreateRequest(
                new BigDecimal("1000000"), PaymentCurrency.VND, null, PaymentMethod.BANK_TRANSFER, "PREPAY-J3"));
        assertEquals("PAID", prepayment.status());

        // 5b. No-show is rejected while that prepayment remains active -- staff must resolve it first.
        ResponseStatusException blockedNoShow = assertThrows(ResponseStatusException.class, () -> reservationService.noShow(
                reservationId, new NoShowReservationRequest("Guest did not arrive and could not be contacted.")));
        assertEquals(409, blockedNoShow.getStatusCode().value());
        assertEquals("CONFIRMED", statusOf(reservationId), "the blocked no-show attempt changes nothing");
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, prepayment.id()));

        // 6. Resolve the prepayment through the currently approved pre-check-in refund workflow.
        prepaymentService.refund(reservationId, prepayment.id(), new PaymentRefundRequest("Guest cancelled before arrival"));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, prepayment.id()));

        // 7-8. No-show now succeeds with the required reason.
        String reason = "Guest did not arrive and could not be contacted.";
        reservationService.noShow(reservationId, new NoShowReservationRequest(reason));
        ReservationDetailResponse afterNoShow = reservationQueryService.findById(reservationId);
        assertEquals("NO_SHOW", afterNoShow.status());
        assertEquals(reason, afterNoShow.noShowReason());
        assertEquals(0, count("SELECT COUNT(*) FROM stay WHERE reservation_id = ?", reservationId), "no Stay is ever created");
        ResponseStatusException terminalCheckIn = assertThrows(ResponseStatusException.class,
                () -> reservationService.checkIn(reservationId));
        assertEquals(409, terminalCheckIn.getStatusCode().value(), "a terminal NO_SHOW reservation can never be checked in");

        // 9. The former room/date inventory is no longer blocked: NO_SHOW does not participate in the
        // lifecycle-aware overlap primitive (only CONFIRMED and CHECKED_IN do).
        assertFalse(availability.hasInventoryConflict(room, checkIn, checkOut));

        // 10. A genuinely new Reservation can use the released inventory for dates overlapping the former
        // no-show booking period, all the way through Confirm and Check-in (late check-in is explicitly
        // allowed by the approved timing rule, since the date has already passed).
        Response replacement = reservationService.create(new CreateRequest(
                guest, checkIn, checkOut, 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room, new BigDecimal("1000000"))), List.of()));
        reservationService.confirm(replacement.id());
        reservationService.checkIn(replacement.id());
        assertEquals("CHECKED_IN", statusOf(replacement.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE room_id = ? AND assigned_to IS NULL", room));

        // Also verify the temporal boundary through the production guard: a same-day or future arrival must
        // never be eligible for No-show.
        UUID todayRoom = room("J3-TODAY", "AVAILABLE");
        Response todayArrival = reservationService.create(new CreateRequest(
                guest, today, today.plusDays(1), 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(todayRoom, new BigDecimal("1000000"))), List.of()));
        reservationService.confirm(todayArrival.id());
        ResponseStatusException notEligible = assertThrows(ResponseStatusException.class, () -> reservationService.noShow(
                todayArrival.id(), new NoShowReservationRequest("attempted too early")));
        assertEquals(409, notEligible.getStatusCode().value());
        assertEquals("CONFIRMED", statusOf(todayArrival.id()));
    }

    // ================================================================================================
    // J4 -- Full Multi-room Hotel Journey
    // ================================================================================================

    /**
     * J4: the broadest V1 operational chain. A two-room Reservation is confirmed and checked in, one occupied
     * room is changed to a third room, the whole Stay is extended, a guest-service Charge is recorded, the
     * folio is settled exactly to zero, checkout closes every currently-occupied room, Housekeeping returns the
     * dirtied rooms to service, and a second, independent Reservation proves the rooms are genuinely reusable --
     * with no stale ReservationRoom, StayRoomAssignment or extension data left blocking them.
     */
    @Test
    void fullMultiRoomHotelJourneyEndsWithTheRoomReusedByANewGuest() {
        // Created through the real RoomService (unlike the other journeys' plain-JDBC bootstrap rooms) because
        // this journey's Housekeeping step drives RoomService.startCleaning/finishCleaning for real, and that
        // production path requires the RoomInventoryPeriod row RoomService.create() seeds; a raw-inserted room
        // has none and RoomInventoryHistoryService correctly refuses to guess one into existence.
        UUID roomA = createRoom("J4-A");
        UUID roomB = createRoom("J4-B");
        UUID roomC = createRoom("J4-C");
        LocalDate checkIn = today;
        LocalDate originalCheckOut = today.plusDays(2);

        // ---- A. Reservation / Confirm ----
        // Two adults, two SINGLE rooms (capacity 1 each): adultCount == totalCapacity, respecting the approved
        // adult-capacity rule exactly at its boundary.
        Response created = reservationService.create(new CreateRequest(
                guest, checkIn, originalCheckOut, 2, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(roomA, new BigDecimal("1000000")), new RoomRequest(roomB, new BigDecimal("1200000"))),
                List.of()));
        UUID reservationId = created.id();
        reservationService.confirm(reservationId);
        List<ReservationRoomResponse> bookedRooms = reservationQueryService.findById(reservationId).rooms();
        assertEquals(2, bookedRooms.size());
        assertTrue(availability.hasInventoryConflict(roomA, checkIn, originalCheckOut));
        assertTrue(availability.hasInventoryConflict(roomB, checkIn, originalCheckOut));

        // ---- B. Check-in ----
        reservationService.checkIn(reservationId);
        UUID stay = stayOf(reservationId);
        assertEquals("CHECKED_IN", statusOf(reservationId));
        assertEquals(2, stayRoomAssignmentQuery.findCurrentRooms(reservationId).size());
        assertEquals(2, count("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM'", stay));
        assertEquals(0, new BigDecimal("2000000").compareTo(jdbc.queryForObject(
                "SELECT amount FROM charge WHERE stay_id = ? AND description = ?", BigDecimal.class, stay, "Room J4-A")));
        assertEquals(0, new BigDecimal("2400000").compareTo(jdbc.queryForObject(
                "SELECT amount FROM charge WHERE stay_id = ? AND description = ?", BigDecimal.class, stay, "Room J4-B")));
        assertEquals(0, new BigDecimal("4400000").compareTo(balances.calculate(stay).totalCharges()));

        // ---- C. Room Change: A -> C ----
        roomChangeService.changeRoom(reservationId, roomA, new RoomChangeRequest(roomC, RoomChangeReason.GUEST_REQUEST, null));
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomA),
                "the vacated room needs housekeeping before it can be offered again");
        assertEquals("OCCUPIED", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomC));
        assertEquals("OCCUPIED", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomB),
                "the untouched second room is not affected by the room change");
        List<UUID> currentRoomIds = stayRoomAssignmentQuery.findCurrentRooms(reservationId).stream()
                .map(CurrentRoomResponse::roomId).toList();
        assertEquals(2, currentRoomIds.size());
        assertTrue(currentRoomIds.contains(roomC));
        assertTrue(currentRoomIds.contains(roomB));
        assertFalse(currentRoomIds.contains(roomA), "the vacated room is no longer a current occupancy");
        List<RoomHistoryLineResponse> history = stayRoomAssignmentQuery.findHistory(reservationId);
        assertEquals(3, history.size(), "one closed line for A, one open for C, one open (untouched) for B");
        assertEquals(1, history.stream().filter(line -> "J4-A".equals(line.roomNumber()) && line.assignedTo() != null).count(),
                "A's assignment is closed, never mutated further");
        // The pricing snapshot is never silently repriced by a Room Change: ReservationRoom keeps the original
        // booked room and its original nightly rate, regardless of which physical room is occupied now.
        List<ReservationRoomResponse> roomsAfterChange = reservationQueryService.findById(reservationId).rooms();
        assertEquals(2, roomsAfterChange.size());
        assertTrue(roomsAfterChange.stream().anyMatch(line -> line.roomId().equals(roomA)
                && line.nightlyRate().compareTo(new BigDecimal("1000000")) == 0
                && line.totalAmount().compareTo(new BigDecimal("2000000")) == 0),
                "ReservationRoom for the booked room A is untouched by the Room Change");
        assertFalse(roomsAfterChange.stream().anyMatch(line -> line.roomId().equals(roomC)),
                "Room Change never creates or rewrites a ReservationRoom line");

        // ---- D. Stay Extension ----
        extensionService.extend(reservationId, new StayExtensionRequest(originalCheckOut, today.plusDays(4)));
        ReservationDetailResponse afterExtension = reservationQueryService.findById(reservationId);
        assertEquals(today.plusDays(4), afterExtension.checkOutDate(), "the planned checkout moves forward");
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", stay));
        // Every open lineage (now-physical room C for the A lineage, and B) is extended at its OWN original rate.
        assertEquals(2, count(
                "SELECT COUNT(*) FROM stay_extension_room ser JOIN stay_extension se ON se.id = ser.stay_extension_id "
                        + "WHERE se.stay_id = ?", stay));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE room_id = ? AND amount = 2000000", roomC),
                "extension of the A lineage (now occupying C) at A's original 1,000,000 rate x 2 nights");
        assertEquals(1, count("SELECT COUNT(*) FROM stay_extension_room WHERE room_id = ? AND amount = 2400000", roomB),
                "extension of the B lineage at B's original 1,200,000 rate x 2 nights");
        // The ORIGINAL ReservationRoom snapshot remains immutable after the extension too.
        List<ReservationRoomResponse> roomsAfterExtension = reservationQueryService.findById(reservationId).rooms();
        assertTrue(roomsAfterExtension.stream().anyMatch(line -> line.roomId().equals(roomA)
                && line.checkOutDate().equals(originalCheckOut)
                && line.totalAmount().compareTo(new BigDecimal("2000000")) == 0),
                "the original booking snapshot keeps its original dates and total, never the extended ones");
        assertEquals(2, stayRoomAssignmentQuery.findCurrentRooms(reservationId).size(),
                "actual occupancy is unaffected by the extension: still exactly C and B open");

        // ---- E. Guest Service Revenue ----
        ChargeResponse serviceCharge = chargeService.create(stay, new ChargeCreateRequest(
                ChargeType.BREAKFAST, "Breakfast for two", new BigDecimal("2"), new BigDecimal("125000"), null));
        assertEquals(0, new BigDecimal("250000").compareTo(
                jdbc.queryForObject("SELECT amount FROM charge WHERE id = ?", BigDecimal.class, serviceCharge.id())));
        assertEquals(1, count("SELECT COUNT(*) FROM additional_revenue WHERE charge_id = ? AND status = 'RECORDED'",
                serviceCharge.id()));

        // ---- F. Payment / Checkout ----
        // Total ACTIVE charges: 2,000,000 + 2,400,000 (original) + 2,000,000 + 2,400,000 (extension) + 250,000
        // (breakfast) = 9,050,000. Computed from the real balance service, not re-derived by the test.
        BigDecimal outstandingBeforePayment = balances.calculate(stay).outstanding();
        assertEquals(0, new BigDecimal("9050000").compareTo(outstandingBeforePayment));
        paymentService.recordPaid(stay, new PaymentCreateRequest(
                outstandingBeforePayment, PaymentCurrency.VND, null, PaymentMethod.CASH, null));
        assertEquals(0, balances.calculate(stay).outstanding().signum());

        reservationService.checkOut(reservationId);
        assertEquals("CHECKED_OUT", statusOf(reservationId));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("SELECT status FROM stay WHERE id = ?", String.class, stay));
        assertNotNull(jdbc.queryForObject("SELECT actual_check_out_at FROM stay WHERE id = ?", java.sql.Timestamp.class, stay));
        // Checkout only closes the CURRENT rooms (C and B); A was already released by the Room Change and is
        // untouched by checkout, but remains DIRTY exactly as the Room Change left it.
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomA));
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomB));
        assertEquals("DIRTY", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomC));
        assertEquals(0, count("SELECT COUNT(*) FROM stay_room_assignment WHERE stay_id = ? AND assigned_to IS NULL", stay),
                "every open assignment is closed by checkout");
        assertEquals(0, balances.calculate(stay).outstanding().signum(), "the folio remains balanced after checkout");

        // ---- G. Housekeeping ----
        for (UUID dirtyRoom : List.of(roomA, roomB, roomC)) {
            roomService.startCleaning(dirtyRoom);
            roomService.finishCleaning(dirtyRoom);
            assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, dirtyRoom));
        }

        // ---- H. Inventory reuse: a second, independent Reservation on room B ----
        // Checked in immediately (today), chronologically after the whole first-guest journey above completed
        // in this same run; an arrival further in the future would correctly trip the EARLY check-in guard.
        LocalDate secondCheckIn = today;
        LocalDate secondCheckOut = today.plusDays(2);
        assertFalse(availability.hasInventoryConflict(roomB, secondCheckIn, secondCheckOut),
                "no stale ReservationRoom/StayRoomAssignment/extension data blocks the completed Stay's room");
        Response secondReservation = reservationService.create(new CreateRequest(
                newGuest("J4-SECOND"), secondCheckIn, secondCheckOut, 1, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(roomB, new BigDecimal("1200000"))), List.of()));
        reservationService.confirm(secondReservation.id());
        reservationService.checkIn(secondReservation.id());

        assertEquals("CHECKED_IN", statusOf(secondReservation.id()));
        assertEquals("OCCUPIED", jdbc.queryForObject("SELECT status FROM room WHERE id = ?", String.class, roomB));
        List<CurrentRoomResponse> secondCurrentRooms = stayRoomAssignmentQuery.findCurrentRooms(secondReservation.id());
        assertEquals(1, secondCurrentRooms.size());
        assertEquals(roomB, secondCurrentRooms.get(0).roomId());
        // The first Stay's history on room B is closed and untouched; only the second Stay holds an open line.
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE room_id = ? AND assigned_to IS NULL", roomB));
        assertEquals(1, count("SELECT COUNT(*) FROM stay_room_assignment WHERE room_id = ? AND assigned_to IS NOT NULL", roomB),
                "the first stay's closed assignment on room B remains, undisturbed history");
    }

    // ---------------------------------------------------------------- fixtures

    /** Creates a new Guest row with a unique code and returns its identifier (bootstrap data, not under test). */
    private UUID newGuest(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, 'Journey', now(), ?, now(), ?)",
                id, "G" + label + "-" + id.toString().substring(0, 8), label, user, user);
        return id;
    }

    /** Creates or resets a bookable SINGLE room (bootstrap data, not under test). */
    private UUID room(String number, String status) {
        jdbc.update("INSERT INTO room (id, room_number, room_type_id, status, active, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, TRUE, now(), ?, now(), ?) ON CONFLICT (room_number) DO UPDATE SET status = EXCLUDED.status",
                UUID.randomUUID(), number, SINGLE_ROOM_TYPE_ID, status, user, user);
        return jdbc.queryForObject("SELECT id FROM room WHERE room_number = ?", UUID.class, number);
    }

    /**
     * Creates a SINGLE room through the real {@link RoomService}, which also seeds the RoomInventoryPeriod row
     * that {@code startCleaning}/{@code finishCleaning} require. Used only where a journey exercises Housekeeping
     * for real; every other journey's rooms are plain bootstrap data via {@link #room(String, String)}.
     */
    private UUID createRoom(String number) {
        return roomService.create(new RoomCreateRequest(number, SINGLE_ROOM_TYPE_ID, "1")).id();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(user, "manager"), null, List.of()));
    }

    private String statusOf(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    private UUID stayOf(UUID reservationId) {
        return jdbc.queryForObject("SELECT id FROM stay WHERE reservation_id = ?", UUID.class, reservationId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }
}
