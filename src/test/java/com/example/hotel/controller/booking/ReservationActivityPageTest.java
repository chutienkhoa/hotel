package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
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
 * Verifies the Reservation Operational Timeline (Activity) section on Reservation Detail: safe
 * localized labels, unknown-action forward compatibility, no raw AuditLog values reaching the
 * page, and VIEW_BOOKING-baseline access without financial amounts.
 */
@WebMvcTest(ReservationPageController.class)
@Import({I18nConfig.class, ReservationActivityPageTest.MethodSecurityTestConfiguration.class})
class ReservationActivityPageTest {

    private static final UUID RESERVATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID GUEST_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID ROOM_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

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
    private com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private JwtService jwtService;

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    /** Confirms known actions render their localized label in both languages, actor included. */
    @Test
    void shouldRenderLocalizedActivityLabelsInBothLanguages() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-16T02:00:00Z"), "admin", "CREATE"),
                new ReservationActivityEntry(Instant.parse("2026-09-16T03:00:00Z"), "reception01", "RECORD_PAYMENT")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).cookie(language("en"))
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"activity-heading\"")))
                .andExpect(content().string(containsString("Reservation created")))
                .andExpect(content().string(containsString("Payment recorded")))
                .andExpect(content().string(containsString("admin")))
                .andExpect(content().string(containsString("reception01")))
                .andExpect(content().string(containsString(">Time<")))
                .andExpect(content().string(containsString(">By<")))
                .andExpect(content().string(containsString(">Activity<")));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).cookie(language("vi"))
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã tạo đặt phòng")))
                .andExpect(content().string(containsString("Đã ghi nhận thanh toán")))
                .andExpect(content().string(containsString(">Thời gian<")))
                .andExpect(content().string(containsString(">Người thực hiện<")))
                .andExpect(content().string(containsString(">Hoạt động<")));
    }

    /** Confirms an unrecognized action never breaks the page and falls back to a generic localized label. */
    @Test
    void shouldFallBackSafelyForUnknownAction() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-16T02:00:00Z"), "admin", "SOME_FUTURE_ACTION")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("SOME_FUTURE_ACTION"))))
                .andExpect(content().string(containsString("Activity recorded")));
    }

    /** Confirms the empty state renders when a Reservation has no recorded activity. */
    @Test
    void shouldRenderEmptyStateForNoActivity() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No activity recorded yet.")));
    }

    /**
     * Confirms a VIEW_BOOKING-only user (no MANAGE_PAYMENT) sees that a financial action occurred
     * but never any amount, currency, method or raw AuditLog value alongside it. Scoped to the
     * Activity section itself, since the page legitimately shows the Reservation total (VND)
     * elsewhere.
     */
    @Test
    void shouldShowFinancialActivityWithoutAmountForViewBookingOnlyUser() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation());
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-16T02:00:00Z"), "reception01", "RECORD_PAYMENT"),
                new ReservationActivityEntry(Instant.parse("2026-09-16T03:00:00Z"), "reception01", "RECORD_CHARGE")));

        String html = mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("v").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String activitySection = html.substring(html.indexOf("id=\"activity\""), html.indexOf("id=\"actions-heading\""));

        org.junit.jupiter.api.Assertions.assertTrue(activitySection.contains("Payment recorded"));
        org.junit.jupiter.api.Assertions.assertTrue(activitySection.contains("Charge recorded"));
        org.junit.jupiter.api.Assertions.assertFalse(activitySection.contains("VND"));
        org.junit.jupiter.api.Assertions.assertFalse(activitySection.contains("CASH"));
        org.junit.jupiter.api.Assertions.assertFalse(activitySection.contains("amount="));
    }

    /** Builds a Reservation Detail response with one booked room. */
    private ReservationDetailResponse reservation() {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260917-000001",
                GUEST_ID,
                "GUEST-001",
                "CONFIRMED",
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 18),
                BigDecimal.TEN,
                "VND",
                null,
                List.of(new ReservationRoomResponse(
                        ROOM_ID, "201", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), BigDecimal.TEN, BigDecimal.TEN)));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
