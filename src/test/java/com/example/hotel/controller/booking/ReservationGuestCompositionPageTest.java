package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.WalkInReviewResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the Adults/Children form controls, validation, draft editing, Reservation Detail and EN/VI labels. */
@WebMvcTest({ReservationPageController.class, CheckInPageController.class})
@Import({ReservationGuestCompositionPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class ReservationGuestCompositionPageTest {

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
    private CheckInService checkInService;

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

    /** Confirms the Create Reservation form defaults to Adults = 1, Children = 0 with whole-number constraints. */
    @Test
    void shouldDefaultCreateFormToOneAdultAndNoChildren() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"adultCount\"")))
                .andExpect(content().string(containsString("min=\"1\"")))
                .andExpect(content().string(containsString("min=\"0\"")))
                .andExpect(content().string(containsString("name=\"adultCount\"")))
                .andExpect(content().string(containsString("value=\"1\"")))
                .andExpect(content().string(containsString("value=\"0\"")))
                .andExpect(content().string(containsString("Adults *")))
                .andExpect(content().string(containsString("Children *")));
    }

    /** Confirms the OTA and Walk-in creation forms expose the same controls with the same defaults. */
    @Test
    void shouldDefaultOtaAndWalkInFormsToOneAdultAndNoChildren() throws Exception {
        for (String path : List.of("/check-in/ota-entry", "/check-in/walk-in")) {
            mockMvc.perform(get(path).with(user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("name=\"adultCount\"")))
                    .andExpect(content().string(containsString("name=\"childCount\"")))
                    .andExpect(content().string(containsString("value=\"1\"")))
                    .andExpect(content().string(containsString("value=\"0\"")));
        }
    }

    /** Confirms the Draft edit form is prefilled with the stored counts. */
    @Test
    void shouldPrefillDraftEditFormWithStoredCounts() throws Exception {
        when(reservationQueryService.findForEdit(RESERVATION_ID)).thenReturn(new ReservationEditResponse(
                RESERVATION_ID, "DRAFT", GUEST_ID, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), 3, 1,
                BookingSource.DIRECT, null, "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "101", LocalDate.of(2026, 9, 20),
                        LocalDate.of(2026, 9, 22), BigDecimal.TEN, BigDecimal.TEN)),
                List.of()));

        mockMvc.perform(get("/reservations/{id}/edit", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"3\"")))
                .andExpect(content().string(containsString("value=\"1\"")));
    }

    /** Confirms a create submission carries the counts to the service. */
    @Test
    void shouldPassSubmittedCountsToTheService() throws Exception {
        when(reservationService.create(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.example.hotel.dto.booking.response.Response(RESERVATION_ID, "R1", "DRAFT", BigDecimal.TEN, "VND"));

        mockMvc.perform(create("3", "1")).andExpect(status().is3xxRedirection());

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(reservationService).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(3, captor.getValue().adultCount());
        org.junit.jupiter.api.Assertions.assertEquals(1, captor.getValue().childCount());
    }

    /** Confirms zero adults, negative children, missing and decimal values are rejected before the service. */
    @Test
    void shouldRejectInvalidCountsBeforeTheService() throws Exception {
        mockMvc.perform(create("0", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Adults must be at least 1.")));
        mockMvc.perform(create("2", "-1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Children must be 0 or more.")));
        mockMvc.perform(create("", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Adults is required.")));
        mockMvc.perform(create("1.5", "0")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Reservation created"))));
        mockMvc.perform(create("1", "0.5")).andExpect(status().isOk());
        verify(reservationService, never()).create(org.mockito.ArgumentMatchers.any());
    }

    /** Confirms validation messages are localized. */
    @Test
    void shouldLocalizeValidationMessages() throws Exception {
        mockMvc.perform(create("0", "0").cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Số người lớn phải từ 1 trở lên.")));
    }

    /** Confirms Reservation Detail shows Adults, Children and the derived Total Guests in English. */
    @Test
    void shouldShowGuestCompositionOnReservationDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(3, 1));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Adults: 3")))
                .andExpect(content().string(containsString("Children: 1")))
                .andExpect(content().string(containsString("Total Guests: 4")))
                .andExpect(content().string(not(containsString("Occupancy"))));
    }

    /** Confirms Reservation Detail labels are Vietnamese when Vietnamese is selected. */
    @Test
    void shouldShowVietnameseGuestCompositionLabels() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(2, 0));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Người lớn: 2")))
                .andExpect(content().string(containsString("Trẻ em: 0")))
                .andExpect(content().string(containsString("Tổng số khách: 2")));
    }

    /** Confirms the Walk-in review page shows the counts and carries them in the confirm form. */
    @Test
    void shouldCarryCountsThroughWalkInReview() throws Exception {
        when(checkInService.reviewWalkIn(org.mockito.ArgumentMatchers.any())).thenReturn(new WalkInReviewResponse(
                GUEST_ID, "Ann Lee", "G-1", false, null, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 23),
                Instant.parse("2026-09-21T03:00:00Z"), List.of(), BigDecimal.TEN, "VND"));

        mockMvc.perform(post("/check-in/walk-in/review")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkOutDate", "2026-09-23")
                        .param("adultCount", "3")
                        .param("childCount", "1")
                        .param("currency", "VND")
                        .param("rooms[0].roomId", ROOM_ID.toString())
                        .param("rooms[0].nightlyRate", "100000")
                        .with(user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"adultCount\" value=\"3\"")))
                .andExpect(content().string(containsString("name=\"childCount\" value=\"1\"")))
                .andExpect(content().string(containsString("Adults: 3")));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String adults, String children) {
        return post("/reservations")
                .param("guestId", GUEST_ID.toString())
                .param("checkInDate", "2027-01-10")
                .param("checkOutDate", "2027-01-12")
                .param("adultCount", adults)
                .param("childCount", children)
                .param("source", "DIRECT")
                .param("currency", "VND")
                .param("rooms[0].roomId", ROOM_ID.toString())
                .param("rooms[0].nightlyRate", "100000")
                .with(manager())
                .with(csrf());
    }

    private static ReservationDetailResponse detail(int adults, int children) {
        return new ReservationDetailResponse(RESERVATION_ID, "R20260917-000001", GUEST_ID, "GUEST-001", "CONFIRMED",
                BookingSource.DIRECT, null, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), adults, children,
                BigDecimal.TEN, "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "201", LocalDate.of(2026, 9, 16),
                        LocalDate.of(2026, 9, 18), BigDecimal.TEN, BigDecimal.TEN)),
                List.of());
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"), new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
