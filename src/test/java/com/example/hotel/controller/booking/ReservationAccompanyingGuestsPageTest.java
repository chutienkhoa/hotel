package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.customer.GuestController;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.customer.GuestService;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies the Accompanying Guest form controls, detail/check-in display, authorization and EN/VI labels. */
@WebMvcTest({ReservationPageController.class, ReservationController.class, CheckInPageController.class, GuestController.class})
@Import({ReservationAccompanyingGuestsPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class ReservationAccompanyingGuestsPageTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMPANION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID ROOM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.example.hotel.service.booking.FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private com.example.hotel.service.room.RoomImageService roomImageService;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private GuestService guestService;

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

    /** Confirms the create form offers the optional Accompanying Guests area (EN and VI) without inline guest creation. */
    @Test
    void shouldOfferAccompanyingGuestsAreaOnTheCreateForm() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(
                new GuestLookupResponse(COMPANION_ID, "G-2", "Bao Tran", null, null, null)));

        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-accompanying-guests")))
                .andExpect(content().string(containsString("Accompanying Guests")))
                .andExpect(content().string(containsString("Add Guest")))
                .andExpect(content().string(containsString("No accompanying guests")))
                .andExpect(content().string(containsString("G-2 · Bao Tran")))
                .andExpect(content().string(containsString("data-message-duplicate=\"Guest already selected.\"")))
                .andExpect(content().string(containsString("Primary Guest cannot be an accompanying guest.")));
        mockMvc.perform(get("/reservations/new").with(manager()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách đi cùng")))
                .andExpect(content().string(containsString("Thêm khách")))
                .andExpect(content().string(containsString("Không có khách đi cùng")))
                .andExpect(content().string(containsString("Khách đã được chọn.")));
    }

    /** Confirms the edit form loads the existing selections as removable entries with submitted ids. */
    @Test
    void shouldLoadExistingSelectionsOnDraftEdit() throws Exception {
        when(reservationQueryService.findForEdit(RESERVATION_ID)).thenReturn(new ReservationEditResponse(
                RESERVATION_ID, "DRAFT", GUEST_ID, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), 3, 1,
                BookingSource.DIRECT, null, "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "101", LocalDate.of(2026, 9, 20),
                        LocalDate.of(2026, 9, 22), BigDecimal.TEN, BigDecimal.TEN)),
                List.of(COMPANION_ID)));
        when(guestQueryService.findAllByIds(List.of(COMPANION_ID))).thenReturn(List.of(
                new GuestLookupResponse(COMPANION_ID, "G-2", "Bao Tran", null, null, null)));

        mockMvc.perform(get("/reservations/{id}/edit", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-guest-id=\"" + COMPANION_ID + "\"")))
                .andExpect(content().string(containsString("name=\"accompanyingGuestIds\" value=\"" + COMPANION_ID + "\"")))
                .andExpect(content().string(containsString("data-accompanying-remove")));
    }

    /** Confirms an MVC create submission carries the accompanying ids to the service. */
    @Test
    void shouldPassAccompanyingIdsFromTheForm() throws Exception {
        when(reservationService.create(any())).thenReturn(new Response(RESERVATION_ID, "R1", "DRAFT", BigDecimal.TEN, "VND"));

        mockMvc.perform(post("/reservations")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkInDate", "2027-01-10").param("checkOutDate", "2027-01-12")
                        .param("adultCount", "3").param("childCount", "1")
                        .param("source", "DIRECT").param("currency", "VND")
                        .param("rooms[0].roomId", ROOM_ID.toString()).param("rooms[0].nightlyRate", "100000")
                        .param("accompanyingGuestIds", COMPANION_ID.toString())
                        .with(manager()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(reservationService).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(COMPANION_ID), captor.getValue().accompanyingGuestIds());
    }

    /** Confirms the REST create request carries the accompanying ids, and MANAGE_GUEST is not required for it. */
    @Test
    void shouldPassAccompanyingIdsFromTheApiWithoutManageGuest() throws Exception {
        when(reservationService.create(any())).thenReturn(new Response(RESERVATION_ID, "R1", "DRAFT", BigDecimal.TEN, "VND"));
        String body = "{\"guestId\":\"" + GUEST_ID + "\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\","
                + "\"adultCount\":2,\"childCount\":0,\"source\":\"DIRECT\",\"currency\":\"VND\","
                + "\"rooms\":[{\"roomId\":\"" + ROOM_ID + "\",\"nightlyRate\":100000}],"
                + "\"accompanyingGuestIds\":[\"" + COMPANION_ID + "\"]}";

        mockMvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(user("booking").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))).with(csrf()))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(reservationService).create(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(COMPANION_ID), captor.getValue().accompanyingGuestIds());
    }

    /** Confirms creating a Guest profile itself still needs MANAGE_GUEST (a booking manager alone is refused). */
    @Test
    void shouldStillRequireManageGuestToCreateAGuestProfile() throws Exception {
        String body = "{\"firstName\":\"Ann\",\"lastName\":\"Lee\",\"nationality\":\"Vietnam\"}";

        mockMvc.perform(post("/api/guests").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(user("booking").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Confirms Reservation Detail separates the Primary Guest from Accompanying Guests for a VIEW_BOOKING user. */
    @Test
    void shouldShowPrimaryAndAccompanyingGuestsSeparatelyOnDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(List.of(
                new AccompanyingGuestResponse(COMPANION_ID, "G-2"))));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Primary Guest")))
                .andExpect(content().string(containsString("Accompanying Guests")))
                .andExpect(content().string(containsString("id=\"accompanying-guests\"")))
                .andExpect(content().string(containsString(">G-2<")))
                .andExpect(content().string(not(containsString("id=\"accompanying-empty\""))))
                .andExpect(content().string(containsString("Known guest profiles; the full party may be larger.")))
                .andExpect(content().string(containsString("Adults: 3")))
                .andExpect(content().string(containsString("Total Guests: 4")))
                .andExpect(content().string(not(containsString("href=\"/guests/" + COMPANION_ID))));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách chính")))
                .andExpect(content().string(containsString("Khách đi cùng")));
    }

    /** Confirms the empty state renders when there are no Accompanying Guests, and no fake guests appear. */
    @Test
    void shouldRenderEmptyAccompanyingState() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(List.of()));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                .andExpect(content().string(containsString("id=\"accompanying-empty\"")))
                .andExpect(content().string(containsString("No accompanying guests")))
                .andExpect(content().string(not(containsString("id=\"accompanying-guests\""))));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Không có khách đi cùng")));
    }

    /** Confirms MANAGE_GUEST users get a link to the accompanying guest profile; others see plain Guest Codes. */
    @Test
    void shouldLinkAccompanyingGuestOnlyForManageGuest() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(List.of(
                new AccompanyingGuestResponse(COMPANION_ID, "G-2"))));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"))))
                .andExpect(content().string(containsString("href=\"/guests/" + COMPANION_ID + "\"")));
    }

    /** Confirms Check-in Review shows adults, children, total guests and accompanying guests, read-only, EN and VI. */
    @Test
    void shouldShowGuestCompositionOnCheckInReview() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review(List.of(
                new AccompanyingGuestResponse(COMPANION_ID, "G-2"))));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"guest-composition\"")))
                .andExpect(content().string(containsString("id=\"review-adults\">3<")))
                .andExpect(content().string(containsString("id=\"review-children\">1<")))
                .andExpect(content().string(containsString("id=\"review-total-guests\">4<")))
                .andExpect(content().string(containsString("id=\"review-accompanying\"")))
                .andExpect(content().string(containsString(">G-2<")))
                .andExpect(content().string(containsString("Confirm Check-in")));
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách đi cùng")))
                .andExpect(content().string(containsString("Tổng số khách")));
    }

    /** Confirms the accompanying empty state on Check-in Review, and that it never changes eligibility. */
    @Test
    void shouldRenderEmptyStateOnCheckInReviewWithoutChangingEligibility() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review(List.of()));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(content().string(containsString("id=\"review-accompanying-empty\"")))
                .andExpect(content().string(containsString("Confirm Check-in")));
    }

    private static ReservationDetailResponse detail(List<AccompanyingGuestResponse> companions) {
        return new ReservationDetailResponse(RESERVATION_ID, "R20260917-000001", GUEST_ID, "GUEST-001", "CONFIRMED",
                BookingSource.DIRECT, null, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), 3, 1, BigDecimal.TEN,
                "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "201", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18),
                        BigDecimal.TEN, BigDecimal.TEN)),
                companions);
    }

    private static CheckInReviewResponse review(List<AccompanyingGuestResponse> companions) {
        return new CheckInReviewResponse(RESERVATION_ID, "R20260915-000001", "CONFIRMED", true, CheckInTiming.NORMAL,
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 15),
                Instant.parse("2026-09-15T10:00:00Z"), BookingSource.DIRECT, null, GUEST_ID, "Nguyen Van A", "GUEST-001",
                "Vietnam", false, List.of(), BigDecimal.TEN, "VND",
                new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of()), 3, 1, companions,
                Instant.parse("2026-08-25T10:00:00Z"), null, null, null, "Nguyen Van A", null, null, true, null, 0L);
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"), new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));
    }

    private static RequestPostProcessor viewer() {
        return user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    private static RequestPostProcessor checkIn() {
        return user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
