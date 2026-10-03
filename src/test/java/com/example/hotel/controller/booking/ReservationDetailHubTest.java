package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Task33 Batch 3D CHECKED_IN Reservation Detail operational hub: the Guest/Stay/
 * Reservation Information cards, Room Details enrichment sourced from the active
 * StayRoomAssignment (never the stale booking snapshot), the Financial Summary card, and the
 * permission/eligibility-aware header contextual actions (Change Room, Checkout).
 */
@WebMvcTest(ReservationPageController.class)
@Import(ReservationDetailHubTest.MethodSecurityTestConfiguration.class)
class ReservationDetailHubTest {

    private static final UUID RESERVATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GUEST_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID STAY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROOM_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID SECOND_ROOM_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

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
    private com.example.hotel.service.booking.StayExtensionService stayExtensionService;

    @MockitoBean
    private com.example.hotel.service.booking.ChargeService chargeService;

    @MockitoBean
    private com.example.hotel.service.booking.PaymentService paymentService;

    @MockitoBean
    private com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private com.example.hotel.service.booking.ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the Guest/Stay/Reservation Information cards render operationally useful data for CHECKED_IN. */
    @Test
    void shouldRenderGuestStayAndReservationInformationCardsForCheckedIn() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(
                new StayResponse(STAY_ID, "CHECKED_IN", Instant.parse("2026-10-02T03:00:00Z"), null));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(
                new GuestLookupResponse(GUEST_ID, "GUEST-001", "Nguyen Van A", "nguyenvana@example.com",
                        "+84912345678", "Vietnam"));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"guest-info-heading\"")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Vietnam")))
                .andExpect(content().string(containsString("+84912345678")))
                .andExpect(content().string(containsString("nguyenvana@example.com")))
                .andExpect(content().string(containsString("id=\"stay-info-heading\"")))
                .andExpect(content().string(containsString("id=\"reservation-summary-info-heading\"")))
                .andExpect(content().string(containsString("id=\"hub-notes-heading\"")));
    }

    /** Confirms the hub cards never render for a non-CHECKED_IN status (no regression to the legacy layout). */
    @Test
    void shouldNotRenderHubCardsForConfirmed() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(
                new ReservationDetailResponse(
                        RESERVATION_ID, "R20261002-000001", GUEST_ID, "GUEST-001", "CONFIRMED",
                        BookingSource.DIRECT, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
                        BigDecimal.TEN, "VND", null, List.of()));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"guest-info-heading\""))))
                .andExpect(content().string(not(containsString("id=\"stay-info-heading\""))))
                .andExpect(content().string(containsString("id=\"reservation-info-heading\"")));
    }

    /** Confirms Room Details shows Room Type and current status sourced from the active StayRoomAssignment room. */
    @Test
    void shouldShowRoomTypeAndStatusFromActiveAssignmentRoom() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(
                List.of(new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, "305", Instant.parse("2026-10-02T03:00:00Z"))));
        when(roomQueryService.findAllByIds(List.of(ROOM_ID))).thenReturn(
                List.of(new RoomResponse(ROOM_ID, "305", new RoomTypeResponse(UUID.randomUUID(), "DBL", "Double Room"),
                        "3", "OCCUPIED", true)));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("status-badge--occupied")));
    }

    /** Confirms the Financial Summary card (Total Charges/Payments/Outstanding) is MANAGE_PAYMENT-gated. */
    @Test
    void shouldShowFinancialSummaryOnlyWithManagePayment() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(
                new StayResponse(STAY_ID, "CHECKED_IN", Instant.parse("2026-10-02T03:00:00Z"), null));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(
                new StayBalance(new BigDecimal("3600000"), new BigDecimal("2000000"), new BigDecimal("1600000")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("payer").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"financial-summary\"")))
                .andExpect(content().string(containsString("3,600,000")))
                .andExpect(content().string(containsString("1,600,000")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"financial-summary\""))));
    }

    /** Confirms the header Change Room action appears only for a single, unambiguous current room with permission. */
    @Test
    void shouldShowHeaderChangeRoomOnlyForSingleCurrentRoomWithPermission() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(
                List.of(new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, "305", Instant.parse("2026-10-02T03:00:00Z"))));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("m").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_CHANGE_ROOM"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"reservation-change-room\"")));

        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(
                new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, "305", Instant.parse("2026-10-02T03:00:00Z")),
                new CurrentRoomResponse(UUID.randomUUID(), SECOND_ROOM_ID, "306", Instant.parse("2026-10-02T03:00:00Z"))));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("m").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_CHANGE_ROOM"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"reservation-change-room\""))));
    }

    /** Confirms the header Checkout action links to the canonical Checkout Review route, gated by PERM_CHECK_OUT. */
    @Test
    void shouldShowHeaderCheckoutReviewLinkOnlyWithCheckOutPermission() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("c").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_CHECK_OUT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"reservation-checkout\"")))
                .andExpect(content().string(containsString("/check-out/" + RESERVATION_ID)));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"reservation-checkout\""))));
    }

    /** Confirms Notes renders Reservation.notes as one read-only value, never a multi-entry thread. */
    @Test
    void shouldRenderNotesAsSingleReadOnlyValue() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20261002-000001", GUEST_ID, "GUEST-001", "CHECKED_IN",
                BookingSource.DIRECT, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
                BigDecimal.TEN, "VND", "Walk-in guest. Requested high floor if possible.", List.of()));

        String body = mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int firstOccurrence = body.indexOf("Walk-in guest. Requested high floor if possible.");
        int lastOccurrence = body.lastIndexOf("Walk-in guest. Requested high floor if possible.");
        org.junit.jupiter.api.Assertions.assertTrue(firstOccurrence > 0);
        org.junit.jupiter.api.Assertions.assertEquals(firstOccurrence, lastOccurrence);
    }

    /** Builds a CHECKED_IN Reservation Detail response with one booked room. */
    private ReservationDetailResponse reservation() {
        return new ReservationDetailResponse(
                RESERVATION_ID, "R20261002-000001", GUEST_ID, "GUEST-001", "CHECKED_IN",
                BookingSource.DIRECT, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
                BigDecimal.TEN, "VND", null, List.of());
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
