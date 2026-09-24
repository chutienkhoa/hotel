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
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.ReservationRoomReassignmentService;
import org.springframework.web.server.ResponseStatusException;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
/**
 * Verifies EXTEND_STAY is the ONLY gate of Stay Extension end to end (real controllers, security and PostgreSQL): a
 * front-desk STAFF profile (CHECK_OUT and EXTEND_STAY, no MANAGE_BOOKING) resolves an overdue stay and extends a normal
 * one, MANAGE_BOOKING alone and CHECK_OUT alone cannot, and ADMIN/MANAGER (holding EXTEND_STAY) still can.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StayExtensionPermissionIntegrationTest {

    private static final UUID SINGLE_ROOM_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

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
        jdbc.update("DELETE FROM audit_log WHERE action IN ('EXTEND_STAY', 'CHECK_OUT')");
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
                + "VALUES (?, ?, 'hash', TRUE, now(), now())", user, "perm." + user);
        guest = UUID.randomUUID();
        jdbc.update("INSERT INTO guest (id, guest_code, first_name, last_name, created_at, created_by, updated_at, updated_by) "
                + "VALUES (?, ?, 'Ann', 'Lee', now(), ?, now(), ?)", guest, "G" + guest.toString().substring(0, 8), user, user);
        today = LocalDate.now(clock);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
        }
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new CurrentUser(user, "actor"), null, authorities));
    }

    /** The default STAFF grants relevant to the front desk (V1 seeds): no MANAGE_BOOKING. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return as("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "MANAGE_PAYMENT", "CHANGE_ROOM", "EXTEND_STAY");
    }

    private UUID overdueStay(UUID room) {
        UUID stay = seededCheckedIn(room, today.minusDays(3), today.minusDays(1));
        UUID line = jdbc.queryForObject("SELECT id FROM reservation_room WHERE reservation_id = ?", UUID.class, reservationOf(stay));
        jdbc.update("INSERT INTO charge (id, stay_id, type, description, quantity, unit_price, amount, charged_at, created_at, created_by, "
                + "updated_at, updated_by, source_reservation_room_id) VALUES (?, ?, 'ROOM', 'Room', 2, 1000000, 2000000, now(), now(), ?, now(), ?, ?)",
                UUID.randomUUID(), stay, user, user, line);
        jdbc.update("INSERT INTO payment (id, stay_id, reservation_id, amount, currency, applied_amount, method, status, paid_at, created_at, "
                + "created_by, updated_at, updated_by) SELECT ?, ?, s.reservation_id, 2000000, 'VND', 2000000, 'CASH', 'PAID', now(), now(), ?, now(), ? "
                + "FROM stay s WHERE s.id = ?", UUID.randomUUID(), stay, user, user, stay);
        return stay;
    }

    private String statusOfReservation(UUID reservation) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservation);
    }

    /** Confirms the full overdue workflow as STAFF (CHECK_OUT + EXTEND_STAY, no MANAGE_BOOKING). */
    @Test
    void shouldLetStaffResolveAnOverdueStayAndCheckOut() throws Exception {
        UUID room = room("EP-A", "OCCUPIED");
        UUID stay = overdueStay(room);
        UUID reservation = reservationOf(stay);

        mockMvc.perform(get("/check-out/{id}", reservation).with(staff()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"overdue-extend-stay\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"overdue-ask-manager\""))));
        mockMvc.perform(post("/check-out/{id}/confirm", reservation).with(staff()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals("CHECKED_IN", statusOfReservation(reservation), "overdue check-out is blocked");

        mockMvc.perform(get("/reservations/{id}/stay-extension", reservation).with(staff())).andExpect(status().isOk());
        mockMvc.perform(post("/reservations/{id}/stay-extension", reservation).with(staff()).with(csrf())
                        .param("expectedCurrentCheckOutDate", today.minusDays(1).toString())
                        .param("newCheckOutDate", today.toString()))
                .andExpect(status().is3xxRedirection());
        assertEquals(today, jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM' AND amount = 1000000", Integer.class, stay));

        mockMvc.perform(post("/reservations/{id}/folio/payments/record-paid", reservation).with(staff()).with(csrf())
                        .param("amount", "1000000").param("currency", "VND").param("method", "CASH"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/check-out/{id}/confirm", reservation).with(staff()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertEquals("CHECKED_OUT", statusOfReservation(reservation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXTEND_STAY' AND entity_id = ? AND user_id = ?",
                Integer.class, reservation, user), "the actor is recorded");
    }

    /** Confirms a normal extension (planned 25/09 vs today 23/09 style) succeeds for STAFF with the original rate. */
    @Test
    void shouldLetStaffExtendANormalStayAtTheOriginalRate() throws Exception {
        UUID room = room("EP-N", "OCCUPIED");
        UUID stay = seededCheckedIn(room, today.minusDays(1), today.plusDays(2));
        UUID reservation = reservationOf(stay);

        mockMvc.perform(post("/api/reservations/{id}/stay-extension", reservation).with(staff()).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCurrentCheckOutDate\":\"" + today.plusDays(2) + "\",\"newCheckOutDate\":\"" + today.plusDays(4) + "\"}"))
                .andExpect(status().isOk());

        assertEquals(today.plusDays(4), jdbc.queryForObject("SELECT check_out_date FROM reservation WHERE id = ?", LocalDate.class, reservation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", Integer.class, stay));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stay_extension_room WHERE nightly_rate = 1000000 AND amount = 2000000 "
                + "AND from_date = ? AND to_date = ?", Integer.class, today.plusDays(2), today.plusDays(4)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charge WHERE stay_id = ? AND type = 'ROOM' AND amount = 2000000", Integer.class, stay));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXTEND_STAY' AND user_id = ?", Integer.class, user));
    }

    /** Confirms users without EXTEND_STAY (CHECK_OUT alone, MANAGE_BOOKING alone) are refused on every entry point. */
    @Test
    void shouldRefuseEveryUserWithoutExtendStay() throws Exception {
        UUID room = room("EP-U", "OCCUPIED");
        UUID stay = overdueStay(room);
        UUID reservation = reservationOf(stay);
        var noExtension = as("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "MANAGE_PAYMENT", "CHANGE_ROOM");
        var bookingOnly = as("VIEW_BOOKING", "MANAGE_BOOKING", "CHECK_OUT");

        for (var caller : List.of(noExtension, bookingOnly)) {
            mockMvc.perform(get("/reservations/{id}/stay-extension", reservation).with(caller)).andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/stay-extension", reservation).with(caller).with(csrf())
                    .param("expectedCurrentCheckOutDate", today.minusDays(1).toString()).param("newCheckOutDate", today.toString()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/reservations/{id}/stay-extension", reservation).with(caller).with(csrf())
                            .contentType("application/json")
                            .content("{\"expectedCurrentCheckOutDate\":\"" + today.minusDays(1) + "\",\"newCheckOutDate\":\"" + today + "\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/check-out/{id}", reservation).with(noExtension))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"overdue-ask-manager\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"overdue-extend-stay\""))));
        mockMvc.perform(get("/reservations/{id}", reservation).with(bookingOnly))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"extend-stay\""))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", Integer.class, stay));
    }

    /** Confirms ADMIN and MANAGER (which hold EXTEND_STAY by default) still extend; MANAGE_BOOKING is irrelevant. */
    @Test
    void shouldStillLetAdminAndManagerExtend() throws Exception {
        UUID room = room("EP-M", "OCCUPIED");
        UUID stay = seededCheckedIn(room, today.minusDays(1), today.plusDays(2));
        UUID reservation = reservationOf(stay);

        mockMvc.perform(post("/api/reservations/{id}/stay-extension", reservation)
                        .with(as("VIEW_BOOKING", "MANAGE_BOOKING", "EXTEND_STAY")).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCurrentCheckOutDate\":\"" + today.plusDays(2) + "\",\"newCheckOutDate\":\"" + today.plusDays(3) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/stay-extension", reservation)
                        .with(as("VIEW_BOOKING", "EXTEND_STAY")).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCurrentCheckOutDate\":\"" + today.plusDays(3) + "\",\"newCheckOutDate\":\"" + today.plusDays(4) + "\"}"))
                .andExpect(status().isOk());

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stay_extension WHERE stay_id = ?", Integer.class, stay));
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
                id, "EP" + id.toString().substring(0, 12), guest, status, in, out, total, user, user);
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
