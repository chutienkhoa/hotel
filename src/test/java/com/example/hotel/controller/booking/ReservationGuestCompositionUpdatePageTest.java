package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.GuestCompositionUpdateException;
import com.example.hotel.exception.GuestCompositionUpdateException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the CONFIRMED guest-composition MVC/REST operation: authorization, UI visibility, prefill, errors, EN/VI. */
@WebMvcTest({ReservationGuestCompositionPageController.class, ReservationController.class, ReservationPageController.class})
@Import({ReservationGuestCompositionUpdatePageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class ReservationGuestCompositionUpdatePageTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMPANION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
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
    private com.example.hotel.service.booking.StayExtensionService stayExtensionService;

    @MockitoBean
    private com.example.hotel.service.booking.FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the detail action appears only for a CONFIRMED reservation and a booking manager. */
    @Test
    void shouldShowTheActionOnlyForEligibleConfirmedReservations() throws Exception {
        String href = "/reservations/" + RESERVATION_ID + "/guest-composition";
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", 2, 1, List.of()));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(containsString(href)))
                .andExpect(content().string(containsString("Edit Guest Composition")));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                .andExpect(content().string(not(containsString(href))));
        for (String status : List.of("DRAFT", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT")) {
            when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(status, 2, 1, List.of()));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                    .andExpect(content().string(not(containsString(href))));
        }
    }

    /** Confirms the form is prefilled, shows the primary guest read-only, and offers only the composition controls. */
    @Test
    void shouldPrefillTheFormAndExposeOnlyCompositionControls() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", 3, 2,
                List.of(new AccompanyingGuestResponse(COMPANION_ID, "G-2"))));
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(
                new GuestLookupResponse(COMPANION_ID, "G-2", "Bao Tran", null, null, null)));
        when(guestQueryService.findAllByIds(List.of(COMPANION_ID))).thenReturn(List.of(
                new GuestLookupResponse(COMPANION_ID, "G-2", "Bao Tran", null, null, null)));

        mockMvc.perform(get("/reservations/{id}/guest-composition", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"composition-primary-guest\">GUEST-001<")))
                .andExpect(content().string(containsString("name=\"adultCount\" value=\"3\"")))
                .andExpect(content().string(containsString("name=\"childCount\" value=\"2\"")))
                .andExpect(content().string(containsString("name=\"accompanyingGuestIds\" value=\"" + COMPANION_ID + "\"")))
                .andExpect(content().string(containsString("data-accompanying-guests")))
                .andExpect(content().string(not(containsString("name=\"guestId\""))))
                .andExpect(content().string(not(containsString("checkInDate"))))
                .andExpect(content().string(not(containsString("nightlyRate"))))
                .andExpect(content().string(not(containsString("name=\"source\""))))
                .andExpect(content().string(containsString("Update Guest Composition")));
        mockMvc.perform(get("/reservations/{id}/guest-composition", RESERVATION_ID).with(manager()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Sửa thành phần khách")))
                .andExpect(content().string(containsString("Cập nhật thành phần khách")))
                .andExpect(content().string(containsString("Người lớn")));
    }

    /** Confirms an ineligible reservation is redirected away from the form with a localized message. */
    @Test
    void shouldRedirectIneligibleReservationsAwayFromTheForm() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN", 2, 0, List.of()));

        mockMvc.perform(get("/reservations/{id}/guest-composition", RESERVATION_ID).with(manager()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage", containsString("has not checked in")));
    }

    /** Confirms a successful update carries the submitted values to the service and returns to the detail page. */
    @Test
    void shouldSubmitTheCompositionAndReturnToDetail() throws Exception {
        when(reservationService.updateConfirmedGuestComposition(eq(RESERVATION_ID), any()))
                .thenReturn(new Response(RESERVATION_ID, "R1", "CONFIRMED", BigDecimal.TEN, "VND"));

        mockMvc.perform(submit("3", "1").param("accompanyingGuestIds", COMPANION_ID.toString()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "Guest composition updated."));

        ArgumentCaptor<GuestCompositionUpdateRequest> captor = ArgumentCaptor.forClass(GuestCompositionUpdateRequest.class);
        verify(reservationService).updateConfirmedGuestComposition(eq(RESERVATION_ID), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(3, captor.getValue().adultCount());
        org.junit.jupiter.api.Assertions.assertEquals(1, captor.getValue().childCount());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(COMPANION_ID), captor.getValue().accompanyingGuestIds());
    }

    /** Confirms invalid form values never reach the service. */
    @Test
    void shouldRejectInvalidValuesBeforeTheService() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", 2, 0, List.of()));

        mockMvc.perform(submit("0", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Adults must be at least 1.")));
        mockMvc.perform(submit("2", "-1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Children must be 0 or more.")));
        verify(reservationService, never()).updateConfirmedGuestComposition(any(), any());
    }

    /** Confirms a capacity rejection re-renders the form with the localized message and its numbers (EN and VI). */
    @Test
    void shouldShowTheLocalizedCapacityErrorOnTheForm() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", 2, 0, List.of()));
        doThrow(new GuestCompositionUpdateException(Reason.INSUFFICIENT_ADULT_CAPACITY, "x", 3, 2))
                .when(reservationService).updateConfirmedGuestComposition(eq(RESERVATION_ID), any());

        mockMvc.perform(submit("3", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("the reservation has 3 adults but the assigned rooms support only 2 adults")));
        mockMvc.perform(submit("3", "0").cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("đặt phòng có 3 người lớn nhưng các phòng được gán chỉ chứa tối đa 2 người lớn")));

        doThrow(new GuestCompositionUpdateException(Reason.CAPACITY_NOT_CONFIGURED, "x", "Suite"))
                .when(reservationService).updateConfirmedGuestComposition(eq(RESERVATION_ID), any());
        mockMvc.perform(submit("1", "0"))
                .andExpect(content().string(containsString("Room capacity is not configured for room type Suite.")));
    }

    /** Confirms a state rejection (already checked in) redirects to the detail page with a localized message. */
    @Test
    void shouldRedirectWithAMessageWhenTheStateNoLongerAllowsTheUpdate() throws Exception {
        doThrow(new GuestCompositionUpdateException(Reason.STAY_ALREADY_EXISTS, "x"))
                .when(reservationService).updateConfirmedGuestComposition(eq(RESERVATION_ID), any());

        mockMvc.perform(submit("2", "0"))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage", containsString("already checked in")));
    }

    /** Confirms MANAGE_BOOKING alone is enough (no MANAGE_GUEST), and VIEW_BOOKING alone or no CSRF is refused. */
    @Test
    void shouldEnforceAuthorization() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", 2, 0, List.of()));
        RequestPostProcessor bookingOnly = user("booking").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));

        mockMvc.perform(get("/reservations/{id}/guest-composition", RESERVATION_ID).with(bookingOnly)).andExpect(status().isOk());
        mockMvc.perform(get("/reservations/{id}/guest-composition", RESERVATION_ID).with(viewer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/guest-composition", RESERVATION_ID).param("adultCount", "2").param("childCount", "0")
                        .with(viewer()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/guest-composition", RESERVATION_ID).param("adultCount", "2").param("childCount", "0")
                        .with(bookingOnly)).andExpect(status().isForbidden());
        verify(reservationService, never()).updateConfirmedGuestComposition(any(), any());
    }

    /** Confirms the narrow REST operation is protected by MANAGE_BOOKING, validates input and forwards the request. */
    @Test
    void shouldExposeANarrowProtectedRestOperation() throws Exception {
        when(reservationService.updateConfirmedGuestComposition(eq(RESERVATION_ID), any()))
                .thenReturn(new Response(RESERVATION_ID, "R1", "CONFIRMED", BigDecimal.TEN, "VND"));
        String body = "{\"adultCount\":3,\"childCount\":1,\"accompanyingGuestIds\":[\"" + COMPANION_ID + "\"]}";
        RequestPostProcessor bookingOnly = user("booking").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));

        mockMvc.perform(post("/api/reservations/{id}/guest-composition", RESERVATION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(bookingOnly).with(csrf())).andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/guest-composition", RESERVATION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(viewer()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/{id}/guest-composition", RESERVATION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adultCount\":0,\"childCount\":0}").with(bookingOnly).with(csrf())).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/reservations/{id}/guest-composition", RESERVATION_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adultCount\":1.5,\"childCount\":0}").with(bookingOnly).with(csrf())).andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String adults, String children) {
        return post("/reservations/{id}/guest-composition", RESERVATION_ID)
                .param("adultCount", adults).param("childCount", children).with(manager()).with(csrf());
    }

    private static ReservationDetailResponse detail(String status, int adults, int children, List<AccompanyingGuestResponse> companions) {
        return new ReservationDetailResponse(RESERVATION_ID, "R20260917-000001", GUEST_ID, "GUEST-001", status,
                BookingSource.DIRECT, null, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), adults, children,
                BigDecimal.TEN, "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "201", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18),
                        BigDecimal.TEN, BigDecimal.TEN)),
                companions);
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"), new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));
    }

    private static RequestPostProcessor viewer() {
        return user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
