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
import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.ReservationFieldUpdateException;
import com.example.hotel.exception.ReservationFieldUpdateException.Reason;
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
import java.math.BigDecimal;
import java.time.Clock;
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

/** Verifies the narrow Booking Contact and Reservation Notes MVC/REST surfaces, lifecycle, and authorization. */
@WebMvcTest({
    ReservationContactAndNotesPageController.class,
    ReservationController.class,
    ReservationPageController.class
})
@Import({ReservationContactAndNotesPageTest.TestConfig.class, I18nConfig.class})
class ReservationContactAndNotesPageTest {

    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
    private com.example.hotel.service.booking.ChargeService chargeService;

    @MockitoBean
    private com.example.hotel.service.booking.PaymentService paymentService;

    @MockitoBean
    private FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private PrepaymentService prepaymentService;

    @MockitoBean
    private com.example.hotel.service.booking.ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private com.example.hotel.service.booking.ReservationDetailEligibilityService detailEligibilityService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms CONFIRMED edits both from its cards, never from the Edit menu, and a viewer sees neither. */
    @Test
    void shouldShowActionsOnlyWhenEditableAndAuthorized() throws Exception {
        for (String status : List.of("CONFIRMED")) {
            when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(status, null, null, null, false));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                    .andExpect(content().string(containsString("id=\"edit-booking-contact-card\"")))
                    .andExpect(content().string(containsString("id=\"edit-notes-card\"")))
                    .andExpect(content().string(not(containsString("id=\"edit-booking-contact\""))))
                    .andExpect(content().string(not(containsString("id=\"edit-notes\""))));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                    .andExpect(content().string(not(containsString("id=\"edit-booking-contact-card\""))))
                    .andExpect(content().string(not(containsString("id=\"edit-notes-card\""))));
        }
    }

    /** Confirms DRAFT and CHECKED_IN edit both from their own cards (no More menu) for MANAGE_BOOKING, never for a viewer. */
    @Test
    void shouldEditContactAndNotesFromTheirCardsWhileDraftOrCheckedIn() throws Exception {
        for (String status : List.of("DRAFT", "CHECKED_IN")) {
            when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(status, null, null, null, false));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                    .andExpect(content().string(containsString("id=\"edit-booking-contact-card\"")))
                    .andExpect(content().string(containsString("id=\"edit-notes-card\"")))
                    .andExpect(content().string(not(containsString("id=\"edit-booking-contact\""))))
                    .andExpect(content().string(not(containsString("id=\"edit-notes\""))))
                    .andExpect(content().string(not(containsString("id=\"reservation-more\""))));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(viewer()))
                    .andExpect(content().string(not(containsString("id=\"edit-booking-contact-card\""))))
                    .andExpect(content().string(not(containsString("id=\"edit-notes-card\""))));
        }
    }

    /** Confirms neither action appears once CHECKED_OUT, CANCELLED or NO_SHOW, even for MANAGE_BOOKING. */
    @Test
    void shouldHideActionsOnceLifecycleIsClosed() throws Exception {
        for (String status : List.of("CHECKED_OUT", "CANCELLED", "NO_SHOW")) {
            when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail(status, null, null, null, false));
            mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                    .andExpect(content().string(not(containsString("id=\"edit-booking-contact\""))))
                    .andExpect(content().string(not(containsString("id=\"edit-notes\""))));
        }
    }

    /** Confirms Reservation Detail shows the Reservation's own Booking Contact snapshot without a fallback notice. */
    @Test
    void shouldShowOwnBookingContactSnapshotOnDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", "Ann Lee", "0900000001", "ann@example.test", false));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(containsString("id=\"booking-contact-name\"")))
                .andExpect(content().string(containsString("Ann Lee")))
                .andExpect(content().string(containsString("0900000001")))
                .andExpect(content().string(not(containsString("id=\"booking-contact-fallback-notice\""))));
    }

    /** Confirms Reservation Detail shows the Primary Guest fallback with an explicit fallback notice. */
    @Test
    void shouldShowPrimaryGuestFallbackWithNoticeWhenNoSnapshot() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", "Ann Lee", "0900000001", "ann@example.test", true));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(manager()))
                .andExpect(content().string(containsString("id=\"booking-contact-fallback-notice\"")));
    }

    /** Confirms the Booking Contact form pre-fills blank (not the Primary Guest fallback) when no snapshot exists. */
    @Test
    void shouldPrefillBookingContactFormBlankWhenOnlyFallbackExists() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", "Ann Lee", "0900000001", "ann@example.test", true));

        mockMvc.perform(get("/reservations/{id}/booking-contact", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"bookingContactName\" value=\"\"")))
                .andExpect(content().string(not(containsString("value=\"Ann Lee\""))));
    }

    /** Confirms the Booking Contact form pre-fills the Reservation's own stored snapshot when it exists. */
    @Test
    void shouldPrefillBookingContactFormFromOwnSnapshot() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID))
                .thenReturn(detail("CONFIRMED", "Front Desk Agency", "0911111111", "agency@example.test", false));

        mockMvc.perform(get("/reservations/{id}/booking-contact", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Front Desk Agency\"")))
                .andExpect(content().string(containsString("value=\"0911111111\"")));
    }

    /** Confirms both narrow forms submit successfully and show localized feedback. */
    @Test
    void shouldSubmitBothNarrowOperations() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", null, null, null, true));
        when(reservationService.updateBookingContact(eq(RESERVATION_ID), any())).thenReturn(response());
        when(reservationService.updateReservationNotes(eq(RESERVATION_ID), any())).thenReturn(response());

        mockMvc.perform(post("/reservations/{id}/booking-contact", RESERVATION_ID)
                        .param("bookingContactName", "Ann Lee")
                        .param("bookingContactPhone", "0900000001")
                        .param("bookingContactEmail", "ann@example.test")
                        .with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "Booking Contact updated."));
        mockMvc.perform(post("/reservations/{id}/notes", RESERVATION_ID)
                        .param("notes", "Guest called ahead")
                        .with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "Reservation notes updated."));

        ArgumentCaptor<BookingContactUpdateRequest> contactCaptor =
                ArgumentCaptor.forClass(BookingContactUpdateRequest.class);
        verify(reservationService).updateBookingContact(eq(RESERVATION_ID), contactCaptor.capture());
        assertEquals("Ann Lee", contactCaptor.getValue().bookingContactName());
        ArgumentCaptor<NotesUpdateRequest> notesCaptor = ArgumentCaptor.forClass(NotesUpdateRequest.class);
        verify(reservationService).updateReservationNotes(eq(RESERVATION_ID), notesCaptor.capture());
        assertEquals("Guest called ahead", notesCaptor.getValue().notes());
    }

    /** Confirms a locked-lifecycle rejection from the service redirects to detail with a safe message. */
    @Test
    void shouldRedirectWithLockedMessageWhenLifecycleClosed() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_OUT", null, null, null, true));
        doThrow(new ReservationFieldUpdateException(Reason.RESERVATION_LOCKED, "locked"))
                .when(reservationService).updateBookingContact(eq(RESERVATION_ID), any());
        doThrow(new ReservationFieldUpdateException(Reason.RESERVATION_LOCKED, "locked"))
                .when(reservationService).updateReservationNotes(eq(RESERVATION_ID), any());

        mockMvc.perform(get("/reservations/{id}/booking-contact", RESERVATION_ID).with(manager()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage",
                        "This reservation's lifecycle no longer allows this change."));
        mockMvc.perform(get("/reservations/{id}/notes", RESERVATION_ID).with(manager()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", null, null, null, true));
        mockMvc.perform(post("/reservations/{id}/booking-contact", RESERVATION_ID)
                        .param("bookingContactName", "X").with(manager()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("errorMessage",
                        "This reservation's lifecycle no longer allows this change."));
    }

    /** Confirms both MVC and REST operations require MANAGE_BOOKING and MVC POSTs require CSRF. */
    @Test
    void shouldEnforceManageBookingOnMvcAndRest() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CONFIRMED", null, null, null, true));
        String contact = "{\"bookingContactName\":\"Ann\"}";
        String notes = "{\"notes\":\"hi\"}";

        mockMvc.perform(get("/reservations/{id}/booking-contact", RESERVATION_ID).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reservations/{id}/notes", RESERVATION_ID).with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/booking-contact", RESERVATION_ID)
                        .param("bookingContactName", "Ann").with(manager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/{id}/booking-contact", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(contact).with(viewer()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/{id}/notes", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(notes).with(viewer()).with(csrf()))
                .andExpect(status().isForbidden());
        verify(reservationService, never()).updateBookingContact(any(), any());
        verify(reservationService, never()).updateReservationNotes(any(), any());
    }

    /** Confirms both narrow REST operations accept a fully blank body (every field individually optional). */
    @Test
    void shouldExposeValidatedNarrowRestOperations() throws Exception {
        when(reservationService.updateBookingContact(eq(RESERVATION_ID), any())).thenReturn(response());
        when(reservationService.updateReservationNotes(eq(RESERVATION_ID), any())).thenReturn(response());

        mockMvc.perform(post("/api/reservations/{id}/booking-contact", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/notes", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/booking-contact", RESERVATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingContactPhone\":\"" + "0".repeat(101) + "\"}")
                        .with(manager()).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static ReservationDetailResponse detail(
            String status, String contactName, String contactPhone, String contactEmail, boolean fromPrimaryGuest) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260924-000001",
                GUEST_ID,
                "GUEST-001",
                status,
                BookingSource.DIRECT,
                null,
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
                List.of(),
                contactName,
                contactPhone,
                contactEmail,
                fromPrimaryGuest);
    }

    private static Response response() {
        return new Response(RESERVATION_ID, "R20260924-000001", "CONFIRMED", BigDecimal.TEN, "VND");
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(Arrays.asList(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
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
