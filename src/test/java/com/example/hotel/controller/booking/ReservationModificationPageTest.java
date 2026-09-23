package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.ConfirmedReservationModificationException;
import com.example.hotel.exception.ConfirmedReservationModificationException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the narrow date-change and OTA-correction MVC/REST surfaces, visibility, i18n and authorization. */
@WebMvcTest({
    ReservationModificationPageController.class,
    ReservationController.class,
    ReservationPageController.class
})
@Import({ReservationModificationPageTest.TestConfig.class, I18nConfig.class})
class ReservationModificationPageTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

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
    private FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private PrepaymentService prepaymentService;

    @MockitoBean
    private com.example.hotel.service.booking.ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms detail actions obey status, Stay, source and MANAGE_BOOKING visibility rules. */
    @Test
    void shouldShowOnlyEligibleAuthorizedDetailActions() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", BookingSource.AGODA));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(containsString("/change-dates")))
                .andExpect(content().string(containsString("/correct-ota-reference")));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                .andExpect(content().string(not(containsString("/change-dates"))))
                .andExpect(content().string(not(containsString("/correct-ota-reference"))));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", BookingSource.DIRECT));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(containsString("/change-dates")))
                .andExpect(content().string(not(containsString("/correct-ota-reference"))));

        when(stayQueryService.findByReservationId(RESERVATION_ID))
                .thenReturn(new StayResponse(UUID.randomUUID(), "CHECKED_IN", Instant.EPOCH, null));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(not(containsString("/change-dates"))))
                .andExpect(content().string(not(containsString("/correct-ota-reference"))));
    }

    /** Confirms an existing Stay does not alter the established prepayment-summary visibility rule. */
    @Test
    void shouldShowPrepaymentSummaryForConfirmedReservationWithStay() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", BookingSource.AGODA));
        when(stayQueryService.findByReservationId(RESERVATION_ID))
                .thenReturn(new StayResponse(UUID.randomUUID(), "CHECKED_IN", Instant.EPOCH, null));
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(new PrepaymentSummaryResponse(
                "VND",
                new BigDecimal("3500000"),
                new BigDecimal("1000000"),
                BigDecimal.ZERO,
                new BigDecimal("1000000"),
                new BigDecimal("2500000"),
                List.of()));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(paymentManager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"prepayments\"")))
                .andExpect(content().string(not(containsString("/change-dates"))))
                .andExpect(content().string(not(containsString("/correct-ota-reference"))));

        verify(prepaymentService).summary(RESERVATION_ID);
    }

    /** Confirms date form is prefilled, localized, narrow and does not expose financial amounts. */
    @Test
    void shouldRenderNarrowLocalizedDateFormWithoutFinancialAmounts() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", BookingSource.AGODA));

        mockMvc.perform(get("/reservations/{id}/change-dates", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"newCheckInDate\"")))
                .andExpect(content().string(containsString("value=\"2026-09-24\"")))
                .andExpect(content().string(containsString("name=\"newCheckOutDate\"")))
                .andExpect(content().string(containsString("value=\"2026-09-26\"")))
                .andExpect(content().string(not(containsString("nightlyRate"))))
                .andExpect(content().string(not(containsString("3500000"))))
                .andExpect(content().string(not(containsString("name=\"source\""))))
                .andExpect(content().string(not(containsString("name=\"currency\""))));

        mockMvc.perform(get("/reservations/{id}/change-dates", RESERVATION_ID).with(manager())
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Đổi ngày đặt phòng")))
                .andExpect(content().string(containsString("Ngày nhận phòng mới")));
    }

    /** Confirms OTA form exposes only the reference, displays source read-only and localizes EN/VI text. */
    @Test
    void shouldRenderNarrowLocalizedOtaForm() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", BookingSource.BOOKING_COM));

        mockMvc.perform(get("/reservations/{id}/correct-ota-reference", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"otaBookingReference\" value=\"OTA-OLD\"")))
                .andExpect(content().string(not(containsString("name=\"source\""))))
                .andExpect(content().string(not(containsString("externalBookingId"))))
                .andExpect(content().string(containsString("Correct OTA Booking Reference")));
        mockMvc.perform(get("/reservations/{id}/correct-ota-reference", RESERVATION_ID).with(manager())
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Sửa mã đặt phòng OTA")));
    }

    /** Confirms successful forms submit only the two narrow request DTOs and return localized feedback. */
    @Test
    void shouldSubmitBothNarrowOperations() throws Exception {
        when(reservationService.changeConfirmedDates(eq(RESERVATION_ID), any()))
                .thenReturn(response());
        when(reservationService.correctOtaBookingReference(eq(RESERVATION_ID), any()))
                .thenReturn(response());

        mockMvc.perform(post("/reservations/{id}/change-dates", RESERVATION_ID)
                        .param("newCheckInDate", "2026-09-25")
                        .param("newCheckOutDate", "2026-09-28")
                        .with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "Reservation dates changed successfully."));
        mockMvc.perform(post("/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .param("otaBookingReference", "NEW-REF")
                        .with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "OTA Booking Reference corrected successfully."));

        ArgumentCaptor<ReservationDateChangeRequest> dateCaptor =
                ArgumentCaptor.forClass(ReservationDateChangeRequest.class);
        verify(reservationService).changeConfirmedDates(eq(RESERVATION_ID), dateCaptor.capture());
        assertEquals(LocalDate.of(2026, 9, 25), dateCaptor.getValue().newCheckInDate());
        assertEquals(LocalDate.of(2026, 9, 28), dateCaptor.getValue().newCheckOutDate());
        ArgumentCaptor<OtaReferenceCorrectionRequest> otaCaptor =
                ArgumentCaptor.forClass(OtaReferenceCorrectionRequest.class);
        verify(reservationService).correctOtaBookingReference(eq(RESERVATION_ID), otaCaptor.capture());
        assertEquals("NEW-REF", otaCaptor.getValue().otaBookingReference());
    }

    /** Confirms Bean Validation rejects missing dates, blank reference and overlength reference before the service. */
    @Test
    void shouldValidateBothFormsBeforeTheService() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", BookingSource.AGODA));

        mockMvc.perform(post("/reservations/{id}/change-dates", RESERVATION_ID)
                        .param("newCheckInDate", "").param("newCheckOutDate", "").with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("New check-in date is required.")));
        mockMvc.perform(post("/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .param("otaBookingReference", "   ").with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OTA Booking Reference is required.")));
        mockMvc.perform(post("/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .param("otaBookingReference", "X".repeat(256)).with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("must not exceed 255 characters")));
        verify(reservationService, never()).changeConfirmedDates(any(), any());
        verify(reservationService, never()).correctOtaBookingReference(any(), any());
    }

    /** Confirms lifecycle failures redirect, while recoverable inventory/prepayment failures remain on the form. */
    @Test
    void shouldPresentLocalizedBusinessFailuresSafely() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", BookingSource.AGODA));
        doThrow(new ConfirmedReservationModificationException(
                Reason.PREPAYMENT_EXCEEDS_TOTAL, "hidden", new BigDecimal("500"), new BigDecimal("400"), "VND"))
                .when(reservationService).changeConfirmedDates(eq(RESERVATION_ID), any());

        mockMvc.perform(post("/reservations/{id}/change-dates", RESERVATION_ID)
                        .param("newCheckInDate", "2026-09-24")
                        .param("newCheckOutDate", "2026-09-25")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Refund the excess prepayment")))
                .andExpect(content().string(not(containsString("500"))))
                .andExpect(content().string(not(containsString("400"))));

        doThrow(new ConfirmedReservationModificationException(Reason.STAY_ALREADY_EXISTS, "hidden"))
                .when(reservationService).correctOtaBookingReference(eq(RESERVATION_ID), any());
        mockMvc.perform(post("/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .param("otaBookingReference", "NEW").with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage", containsString("already has a Stay")));
    }

    /** Confirms both MVC and REST operations require MANAGE_BOOKING and MVC POSTs require CSRF. */
    @Test
    void shouldEnforceManageBookingOnMvcAndRest() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", BookingSource.AGODA));
        String dates = "{\"newCheckInDate\":\"2026-09-25\",\"newCheckOutDate\":\"2026-09-28\"}";
        String ota = "{\"otaBookingReference\":\"NEW\"}";

        mockMvc.perform(get("/reservations/{id}/change-dates", RESERVATION_ID).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reservations/{id}/correct-ota-reference", RESERVATION_ID).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/change-dates", RESERVATION_ID)
                        .param("newCheckInDate", "2026-09-25").param("newCheckOutDate", "2026-09-28")
                        .with(manager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/{id}/change-dates", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(dates).with(viewer()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ota).with(viewer()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms both narrow REST operations accept valid input for MANAGE_BOOKING and reject malformed input. */
    @Test
    void shouldExposeValidatedNarrowRestOperations() throws Exception {
        when(reservationService.changeConfirmedDates(eq(RESERVATION_ID), any())).thenReturn(response());
        when(reservationService.correctOtaBookingReference(eq(RESERVATION_ID), any())).thenReturn(response());

        mockMvc.perform(post("/api/reservations/{id}/change-dates", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newCheckInDate\":\"2026-09-25\",\"newCheckOutDate\":\"2026-09-28\"}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otaBookingReference\":\"NEW\"}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/change-dates", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/reservations/{id}/correct-ota-reference", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otaBookingReference\":\"   \"}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static ReservationDetailResponse detail(String status, BookingSource source) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260924-000001",
                GUEST_ID,
                "GUEST-001",
                status,
                source,
                source == BookingSource.DIRECT ? null : "OTA-OLD",
                LocalDate.of(2026, 9, 24),
                LocalDate.of(2026, 9, 26),
                1,
                0,
                new BigDecimal("3500000"),
                "VND",
                "keep",
                List.of(new ReservationRoomResponse(
                        ROOM_ID,
                        "101",
                        LocalDate.of(2026, 9, 24),
                        LocalDate.of(2026, 9, 26),
                        new BigDecimal("1750000"),
                        new BigDecimal("3500000"))),
                List.of());
    }

    private static Response response() {
        return new Response(RESERVATION_ID, "R20260924-000001", "CONFIRMED", BigDecimal.TEN, "VND");
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));
    }

    private static RequestPostProcessor paymentManager() {
        return user("payment-manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));
    }

    private static RequestPostProcessor viewer() {
        return user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /** Supplies method security and the deterministic hotel Clock. */
    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {

        /**
         * Supplies the deterministic hotel business Clock.
         *
         * @return fixed Clock on 24 September 2026 in the hotel timezone
         */
        @Bean
        Clock clock() {
            ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
            return Clock.fixed(LocalDate.of(2026, 9, 24).atTime(10, 0).atZone(zone).toInstant(), zone);
        }
    }
}
