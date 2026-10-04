package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.SecurityConfig;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationListRoomResponse;
import com.example.hotel.dto.booking.response.ReservationListRowResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.JwtService;
import com.example.hotel.security.SessionUserDetailsService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression coverage for the Reservation List -> Reservation Detail navigation, run through the
 * REAL production {@link SecurityConfig} filter chain and the REAL Thymeleaf template (not a
 * method-security-only test stub), since that stub cannot reveal a security-chain-level routing
 * problem, and a mocked {@code StayQueryService.findByReservationId} for every status cannot reveal
 * a bug in the "does this Reservation have a Stay" check itself.
 *
 * <p>DRAFT/CONFIRMED/CANCELLED/NO_SHOW never have a Stay; CHECKED_IN/CHECKED_OUT always do. This
 * class deliberately never stubs {@code stayQueryService.findByReservationId} to return a Stay for
 * the first group and never stubs {@code existsByReservationId} to {@code true} for it either,
 * matching the real repository behavior ({@code Stay} rows only ever exist after check-in).</p>
 */
@WebMvcTest(ReservationPageController.class)
@Import({SecurityConfig.class, SessionUserDetailsService.class})
class ReservationDetailRoutingTest {

    private static final UUID RESERVATION_ID = UUID.fromString("6040d2c5-ca1c-3d83-875e-4e745dcf28f6");
    private static final UUID GUEST_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private StayRoomAssignmentQueryService stayRoomAssignmentQueryService;

    @MockitoBean
    private StayExtensionService stayExtensionService;

    @MockitoBean
    private com.example.hotel.service.booking.ChargeService chargeService;

    @MockitoBean
    private com.example.hotel.service.booking.PaymentService paymentService;

    @MockitoBean
    private com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Confirms Reservation Detail renders for a lifecycle state that never has a Stay
     * (DRAFT/CONFIRMED/CANCELLED/NO_SHOW), without ever calling the throwing
     * {@code findByReservationId} — only the non-throwing existence check is stubbed, exactly
     * matching what {@code StayRepository.existsByReservationId} returns for such a Reservation.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CONFIRMED", "CANCELLED", "NO_SHOW"})
    void shouldRenderDetailForLifecycleStatesWithoutAStay(String status) throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation(status));
        when(stayQueryService.existsByReservationId(RESERVATION_ID)).thenReturn(false);
        // Matches the real StayQueryService.findByReservationId contract for a Reservation with no
        // Stay (it throws NOT_FOUND, it never returns null) — stubbed explicitly so this test would
        // fail if the production code ever again called this throwing method to answer a plain
        // existence question, exactly the historical bug this test exists to catch.
        when(stayQueryService.findByReservationId(RESERVATION_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk());
    }

    /**
     * Confirms Reservation Detail renders for a lifecycle state that always has a Stay
     * (CHECKED_IN/CHECKED_OUT), with the Stay's existence stubbed {@code true} — the real relationship
     * for these two statuses — and never a null/missing Stay.
     */
    @ParameterizedTest
    @ValueSource(strings = {"CHECKED_IN", "CHECKED_OUT"})
    void shouldRenderDetailForLifecycleStatesWithAStay(String status) throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation(status));
        when(stayQueryService.existsByReservationId(RESERVATION_ID)).thenReturn(true);
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk());
    }

    /**
     * Confirms the new Void Prepayment action renders correctly for an eligible CONFIRMED
     * Reservation (no Stay, a PAID prepayment, MANAGE_PAYMENT authority) — the exact branch the Void
     * Prepayment form lives in, and the one the earlier, now-replaced diagnostic test never actually
     * exercised (it authenticated with VIEW_BOOKING only, so prepaymentSummary was always null).
     */
    @Test
    void shouldRenderVoidPrepaymentActionForEligibleConfirmedReservation() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));
        when(stayQueryService.existsByReservationId(RESERVATION_ID)).thenReturn(false);
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of());
        UUID paymentId = UUID.randomUUID();
        PaymentResponse prepayment = new PaymentResponse(
                paymentId, null, new BigDecimal("1000000"), "VND", null, new BigDecimal("1000000"),
                "CASH", "PAID", Instant.parse("2026-11-01T03:00:00Z"), null, null, null);
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(new PrepaymentSummaryResponse(
                "VND", BigDecimal.TEN, new BigDecimal("1000000"), BigDecimal.ZERO,
                new BigDecimal("1000000"), BigDecimal.ZERO, List.of(prepayment)));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/reservations/" + RESERVATION_ID + "/prepayments/" + paymentId + "/void")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Void Prepayment")));
    }

    /** Confirms a genuinely nonexistent Reservation still produces the existing not-found behavior, not a routing bug. */
    @Test
    void shouldReturnNotFoundForGenuinelyMissingReservation() throws Exception {
        UUID missing = UUID.randomUUID();
        when(reservationQueryService.findById(missing))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));

        mockMvc.perform(get("/reservations/{id}", missing)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isNotFound());
    }

    /** Confirms authorization is unchanged: a user without VIEW_BOOKING is forbidden (403), not silently 404'd. */
    @Test
    void shouldForbidUserWithoutViewBookingRatherThanNotFound() throws Exception {
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_MANAGE_EXPENSE"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Reservation List renders each row's detail link using Reservation.id, not another identifier. */
    @Test
    void shouldRenderListDetailLinkUsingReservationId() throws Exception {
        ReservationListRowResponse row = new ReservationListRowResponse(
                RESERVATION_ID, "R20261130-000100", "Ann Lee", "G000001", LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 12, 2), 2, List.of(new ReservationListRoomResponse("101", "Single Room")),
                BookingSource.DIRECT, null, ReservationStatus.DRAFT);
        when(reservationQueryService.findListPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/reservations")
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/reservations/" + RESERVATION_ID)));
    }

    private ReservationDetailResponse reservation(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID, "R20261130-000100", GUEST_ID, "GUEST-001", status,
                BookingSource.DIRECT, null, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 2),
                BigDecimal.TEN, "VND", null, List.of());
    }
}
