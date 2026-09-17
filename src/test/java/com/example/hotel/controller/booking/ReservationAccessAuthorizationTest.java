package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayBalance;
import java.time.Instant;
import java.time.LocalDate;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies reservation read and write authorization for the approved role permissions. */
@WebMvcTest({ReservationController.class, ReservationPageController.class})
@Import(ReservationAccessAuthorizationTest.MethodSecurityTestConfiguration.class)
class ReservationAccessAuthorizationTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

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
    private JwtService jwtService;

    /**
     * Confirms that each approved role can read both reservation resources through VIEW_BOOKING.
     *
     * @param username representative username for the tested role
     * @param authorities permissions mapped from that role
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("reservationViewUsers")
    void shouldAllowApprovedRolesToAccessReservationListAndDetail(
            String username, List<SimpleGrantedAuthority> authorities) throws Exception {
        when(reservationQueryService.findAll()).thenReturn(List.of());
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(null);

        mockMvc.perform(get("/api/reservations").with(user(username).authorities(authorities)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reservations/{id}", RESERVATION_ID)
                        .with(user(username).authorities(authorities)))
                .andExpect(status().isOk());
    }

    /**
     * Confirms that only MANAGE_BOOKING continues to authorize confirmation.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @ParameterizedTest
    @MethodSource("reservationManagers")
    void shouldKeepReservationWritePermissionForAdministrativeRoles(
            String username, List<SimpleGrantedAuthority> authorities) throws Exception {
        when(reservationService.confirm(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CONFIRMED",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .with(user(username).authorities(authorities))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * Confirms that VIEW_BOOKING alone does not grant reservation write access.
     *
     * @throws Exception if MockMvc cannot perform the request
     */
    @Test
    void shouldNotGrantReservationWritePermissionToStaff() throws Exception {
        mockMvc.perform(post("/api/reservations/{id}/confirm", RESERVATION_ID)
                        .with(user("staff").authorities(viewBookingAuthority()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms that CHECK_IN remains available to STAFF and unavailable to administrative roles.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldKeepCheckInPermissionForStaffOnly() throws Exception {
        when(reservationService.checkIn(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CHECKED_IN",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/check-in", RESERVATION_ID)
                        .with(user("staff").authorities(checkInAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/check-in", RESERVATION_ID)
                        .with(user("admin").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * Confirms CHECK_OUT is required by both REST and CSRF-protected browser check-out operations.
     *
     * @throws Exception if MockMvc cannot perform the requests
     */
    @Test
    void shouldRequireCheckOutPermissionAndCsrfForCheckout() throws Exception {
        when(reservationService.checkOut(RESERVATION_ID))
                .thenReturn(
                        new Response(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "CHECKED_OUT",
                                BigDecimal.ONE,
                                "JPY"));

        mockMvc.perform(post("/api/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/check-out", RESERVATION_ID)
                        .with(user("staff").authorities(checkOutAuthority()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** Confirms a payment manager can discover the Folio link for a Reservation with a Stay. */
    @Test
    void shouldShowFolioLinkToManagePaymentUserForCheckedInReservation() throws Exception {
        stubCheckedInReservation(BigDecimal.ZERO);

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(viewAndManagePaymentAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("View Folio")));
    }

    /** Confirms a check-out-only user receives readiness without detailed Folio financial access. */
    @Test
    void shouldShowNonFinancialReadinessToCheckOutOnlyUser() throws Exception {
        stubCheckedInReservation(BigDecimal.TEN);

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("staff").authorities(viewAndCheckOutAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment required before check-out.")))
                .andExpect(content().string(not(containsString("View Folio"))))
                .andExpect(content().string(not(containsString("Total Charges"))));
    }

    /** Confirms VND Reservation totals and assigned-room values use grouped zero-decimal display. */
    @Test
    void shouldFormatVndAmountsOnReservationDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                new BigDecimal("123456789.000000"),
                "VND",
                null,
                List.of(new ReservationRoomResponse(
                        UUID.randomUUID(),
                        "101",
                        LocalDate.of(2026, 9, 11),
                        LocalDate.of(2026, 9, 12),
                        new BigDecimal("5500000.000000"),
                        new BigDecimal("1100000.000000")))));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("123,456,789 VND")))
                .andExpect(content().string(containsString("5,500,000 VND")))
                .andExpect(content().string(containsString("1,100,000 VND")));
    }

    /** Confirms reservation mutation forms expose confirmation metadata without changing their CSRF fields. */
    @Test
    void shouldRenderReservationConfirmationMetadata() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reservation-create")))
                .andExpect(content().string(containsString("data-confirm-title=\"Create reservation\"")))
                .andExpect(content().string(containsString("data-confirm-message=\"Create this reservation?\"")))
                .andExpect(content().string(containsString("data-confirm-label=\"Create reservation\"")))
                .andExpect(content().string(containsString("data-confirm-severity=\"NORMAL\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-confirm-title=\"Cancel reservation\"")))
                .andExpect(content().string(containsString("data-confirm-severity=\"DANGER\"")))
                .andExpect(content().string(containsString("Cancel reservation R20260911-000001?")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("staff").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/check-in/reservations/" + RESERVATION_ID + "\">Check-in</a>")));
    }

    /** Confirms the reservation Guest field remains one native select with collapsible details. */
    @Test
    void shouldRenderNativeGuestSelectWithoutGuestSearchControl() throws Exception {
        GuestLookupResponse guest = new GuestLookupResponse(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "G000125",
                "Nguyen Van A",
                "guest@example.com",
                "0901234567",
                "Vietnam");
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guest));
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<select data-guest-select")))
                .andExpect(content().string(containsString("G000125 · Nguyen Van A")))
                .andExpect(content().string(containsString("data-email=\"guest@example.com\"")))
                .andExpect(content().string(containsString("<summary>Guest Information</summary>")))
                .andExpect(content().string(containsString("Guest Code")))
                .andExpect(content().string(containsString("Full Name")))
                .andExpect(content().string(containsString("Nationality")))
                .andExpect(content().string(not(containsString("data-guest-search"))))
                .andExpect(content().string(not(containsString("guest-lookup.js"))))
                .andExpect(content().string(not(containsString("Passport"))))
                .andExpect(content().string(not(containsString("Identity document"))));
    }

    /** Confirms the Reservation Create page limits currency selection to VND and USD. */
    @Test
    void shouldRenderCurrencySelectAndPreserveSelectedCurrencyAfterValidationFailure() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<select id=\"currency\"")))
                .andExpect(content().string(containsString("value=\"VND\">VND")))
                .andExpect(content().string(containsString("value=\"USD\">USD")))
                .andExpect(content().string(not(containsString("<input id=\"currency\""))))
                .andExpect(content().string(containsString("class=\"button button-danger remove-room\"")));

        mockMvc.perform(post("/reservations")
                        .param("currency", "USD")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"USD\" selected=\"selected\"")));
    }

    /** Confirms Reservation date fields opt in to the shared non-native date picker assets. */
    @Test
    void shouldRenderSharedDatePickerForReservationFiltersAndCreateForm() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/reservations")
                        .param("checkInFrom", "2026-09-01")
                        .param("checkOutTo", "2026-09-30")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/css/vendor/flatpickr-4.6.13.min.css\"")))
                .andExpect(content().string(containsString(
                        "src=\"/js/vendor/flatpickr-4.6.13.min.js\"")))
                .andExpect(content().string(containsString("src=\"/js/common/date-picker.js\"")))
                .andExpect(content().string(containsString("class=\"js-date-picker\"")))
                .andExpect(content().string(containsString("value=\"2026-09-01\"")))
                .andExpect(content().string(containsString("value=\"2026-09-30\"")))
                .andExpect(content().string(not(containsString("type=\"date\""))));

        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"checkInDate\"")))
                .andExpect(content().string(containsString("id=\"checkOutDate\"")))
                .andExpect(content().string(containsString("class=\"js-date-picker\"")))
                .andExpect(content().string(not(containsString("type=\"date\""))));
    }

    /** Confirms ISO dates submitted by the date picker continue to bind to LocalDate. */
    @Test
    void shouldBindIsoReservationDatesSubmittedByDatePicker() throws Exception {
        UUID guestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID roomId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(reservationService.create(any())).thenReturn(new Response(
                RESERVATION_ID,
                "R20260911-000001",
                "DRAFT",
                BigDecimal.TEN,
                "VND"));

        mockMvc.perform(post("/reservations")
                        .param("guestId", guestId.toString())
                        .param("checkInDate", "2027-01-10")
                        .param("checkOutDate", "2027-01-12")
                        .param("source", "DIRECT")
                        .param("currency", "VND")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "100000")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID));

        ArgumentCaptor<CreateRequest> requestCaptor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(reservationService).create(requestCaptor.capture());
        assertEquals(guestId, requestCaptor.getValue().guestId());
        assertEquals(LocalDate.of(2027, 1, 10), requestCaptor.getValue().checkInDate());
        assertEquals(LocalDate.of(2027, 1, 12), requestCaptor.getValue().checkOutDate());
    }

    /** Confirms a booking manager can open a prepopulated edit form for a draft Reservation. */
    @Test
    void shouldRenderPrepopulatedEditFormForDraftReservation() throws Exception {
        UUID guestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID roomId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(reservationQueryService.findForEdit(RESERVATION_ID)).thenReturn(new ReservationEditResponse(
                RESERVATION_ID,
                "DRAFT",
                guestId,
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 22),
                com.example.hotel.entity.booking.BookingSource.AGODA,
                "AG-998877",
                "VND",
                "Quiet room",
                List.of(new ReservationRoomResponse(
                        roomId,
                        "101",
                        LocalDate.of(2026, 9, 20),
                        LocalDate.of(2026, 9, 22),
                        new BigDecimal("1200000"),
                        new BigDecimal("2400000")))));
        when(guestQueryService.findAllForReservationEditing(guestId)).thenReturn(List.of(new GuestLookupResponse(
                guestId, "G000125", "Nguyen Van A", "guest@example.com", "0901234567", "Vietnam")));
        when(roomQueryService.findAllForReservationEditing(List.of(roomId))).thenReturn(List.of(
                new RoomLookupResponse(UUID.randomUUID(), "DEMO-302", "AVAILABLE", true),
                new RoomLookupResponse(roomId, "DEMO-101", "OCCUPIED", true)));

        mockMvc.perform(get("/reservations/{id}/edit", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit reservation")))
                .andExpect(content().string(containsString("Save Changes")))
                .andExpect(content().string(containsString("data-confirm-title=\"Save changes\"")))
                .andExpect(content().string(containsString("data-confirm-message=\"Save changes to this reservation?\"")))
                .andExpect(content().string(containsString("data-confirm-label=\"Save Changes\"")))
                .andExpect(content().string(containsString(">DEMO-302</option>")))
                .andExpect(content().string(containsString("DEMO-101 — OCCUPIED")))
                .andExpect(content().string(containsString("id=\"checkInDate\"")))
                .andExpect(content().string(containsString("value=\"2026-09-20\"")))
                .andExpect(content().string(containsString("value=\"2026-09-22\"")))
                .andExpect(content().string(not(containsString("value=\"2026-01-01\""))))
                .andExpect(content().string(containsString("value=\"AGODA\" selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"AG-998877\"")))
                .andExpect(content().string(containsString("Quiet room")))
                .andExpect(content().string(containsString("class=\"js-money-input\"")));
    }

    /** Confirms edit remains a MANAGE_BOOKING operation and its POST is CSRF protected. */
    @Test
    void shouldRequireManageBookingAndCsrfForDraftEdit() throws Exception {
        mockMvc.perform(get("/reservations/{id}/edit", RESERVATION_ID)
                        .with(user("staff").authorities(viewBookingAuthority())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/reservations/{id}/edit", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a valid draft edit uses the existing request binding and redirects to detail. */
    @Test
    void shouldSubmitDraftEditAndRedirectToReservationDetail() throws Exception {
        UUID guestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID roomId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(reservationService.updateDraft(eq(RESERVATION_ID), any())).thenReturn(new Response(
                RESERVATION_ID, "R20260911-000001", "DRAFT", BigDecimal.TEN, "VND"));

        mockMvc.perform(post("/reservations/{id}/edit", RESERVATION_ID)
                        .param("guestId", guestId.toString())
                        .param("checkInDate", "2027-01-10")
                        .param("checkOutDate", "2027-01-12")
                        .param("source", "DIRECT")
                        .param("currency", "VND")
                        .param("rooms[0].roomId", roomId.toString())
                        .param("rooms[0].nightlyRate", "1200000")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID));

        verify(reservationService).updateDraft(eq(RESERVATION_ID), any(CreateRequest.class));
    }

    /** Confirms detail exposes Edit only for a draft Reservation to a booking manager. */
    @Test
    void shouldShowEditOnlyForDraftReservationToManageBookingUser() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("DRAFT"));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/reservations/" + RESERVATION_ID + "/edit")));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/reservations/" + RESERVATION_ID + "/edit"))));
    }

    /** Confirms Reservation list and detail retain the shared Reservations navigation page class. */
    @Test
    void shouldRenderReservationsNavigationForListAndDetail() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));

        mockMvc.perform(get("/reservations").with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reservations")))
                .andExpect(content().string(containsString("nav-reservations")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reservations")))
                .andExpect(content().string(containsString("nav-reservations")));
    }

    /** Confirms Reservations stays active while creating a reservation, a child of the Reservations section. */
    @Test
    void shouldKeepReservationsActiveOnCreateReservationChildRoute() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reservation-create")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reservations\"")));
    }

    /** Confirms the ADMIN sidebar renders every currently implemented and authorized V1 section/item. */
    @Test
    void shouldRenderAdminSidebarNavigation() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String body = mockMvc.perform(get("/reservations").with(user("admin").authorities(fullAdminAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-dashboard\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reservations\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-guests\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-rooms\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-expenses\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-additional-revenues\"")))
                .andExpect(content().string(containsString(">Operations<")))
                .andExpect(content().string(containsString(">Hotel<")))
                .andExpect(content().string(containsString(">Finance<")))
                .andReturn().getResponse().getContentAsString();

        assertFakeNavigationAbsent(body);
    }

    /** Confirms the MANAGER sidebar shows Guests, Expenses, and Additional Revenue but never Users. */
    @Test
    void shouldRenderManagerSidebarNavigation() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String body = mockMvc.perform(get("/reservations").with(user("manager").authorities(fullManagerAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-guests\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-expenses\"")))
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-additional-revenues\"")))
                .andReturn().getResponse().getContentAsString();

        assertFakeNavigationAbsent(body);
    }

    /** Confirms STAFF sees only Reservations and never Guests, Expenses, Additional Revenue, or Dashboard. */
    @Test
    void shouldRenderStaffSidebarNavigation() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String body = mockMvc.perform(get("/reservations").with(user("staff").authorities(fullStaffAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-nav-link nav-reservations\"")))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-dashboard\""))))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-guests\""))))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-rooms\""))))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-expenses\""))))
                .andExpect(content().string(not(containsString("class=\"sidebar-nav-link nav-additional-revenues\""))))
                .andReturn().getResponse().getContentAsString();

        assertFakeNavigationAbsent(body);
    }

    /** Confirms empty sections (Hotel, Finance) render no heading at all for STAFF, never an empty group. */
    @Test
    void shouldHideEmptySidebarSectionHeadingsForStaff() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/reservations").with(user("staff").authorities(fullStaffAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Operations<")))
                .andExpect(content().string(not(containsString(">Hotel<"))))
                .andExpect(content().string(not(containsString(">Finance<"))));
    }

    /**
     * Confirms no sidebar item exists for a target-mockup feature without a real V1 route: no
     * Housekeeping, Hotel Settings, Staff, Users, standalone Payments, or Reports item.
     *
     * @param body rendered page HTML
     */
    private void assertFakeNavigationAbsent(String body) {
        assertEquals(false, body.contains("Housekeeping"));
        assertEquals(false, body.contains("Hotel Settings"));
        assertEquals(false, body.contains(">Staff<"));
        assertEquals(false, body.contains(">Users<"));
        assertEquals(false, body.contains("nav-payments"));
        assertEquals(false, body.contains(">Overview<"));
        assertEquals(false, body.contains(">Financial<"));
        assertEquals(false, body.contains(">Occupancy<"));
        assertEquals(false, body.contains("href=\"#\""));
        assertEquals(false, body.contains("javascript:void(0)"));
    }

    /** Confirms the reservation number is the sole detail link in the reservation list. */
    @Test
    void shouldRenderReservationNumberAsDetailLinkWithoutViewColumn() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(
                        List.of(new ReservationSummaryResponse(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "Nguyen Van A",
                                "101",
                                "CONFIRMED",
                                com.example.hotel.entity.booking.BookingSource.DIRECT,
                                null,
                                LocalDate.of(2026, 9, 11),
                                LocalDate.of(2026, 9, 12),
                                BigDecimal.TEN,
                                "VND")),
                        PageRequest.of(0, 10),
                        1));

        mockMvc.perform(get("/reservations").with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/reservations/" + RESERVATION_ID + "\">R20260911-000001</a>")))
                .andExpect(content().string(not(containsString("View details"))))
                .andExpect(content().string(not(containsString(">View</a>"))));
    }

    /** Confirms filters are preserved in page links and use the requested server-side page. */
    @Test
    void shouldRenderFilterPreservingReservationPagination() throws Exception {
        when(reservationQueryService.findPage(any(), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 23));

        mockMvc.perform(get("/reservations")
                        .param("reservationNumber", " R2026 ")
                        .param("status", "CHECKED_IN")
                        .param("checkInFrom", "2026-01-01")
                        .param("checkInTo", "2026-12-31")
                        .param("checkOutFrom", "2026-01-02")
                        .param("checkOutTo", "2027-01-01")
                        .param("page", "1")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("23")))
                .andExpect(content().string(containsString("class=\"reservation-filter-grid\"")))
                .andExpect(content().string(containsString("class=\"action-row reservation-filter-actions\"")))
                .andExpect(content().string(containsString("class=\"results-toolbar\"")))
                .andExpect(content().string(containsString("class=\"pagination reservation-pagination\"")))
                .andExpect(content().string(containsString("value=\"R2026\"")))
                .andExpect(content().string(containsString("value=\"CHECKED_IN\" selected=\"selected\"")))
                .andExpect(content().string(containsString("page=0")))
                .andExpect(content().string(containsString("page=2")))
                .andExpect(content().string(containsString("reservationNumber=R2026")))
                .andExpect(content().string(containsString("status=CHECKED_IN")));

        ArgumentCaptor<ReservationSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(ReservationSearchCriteria.class);
        verify(reservationQueryService).findPage(criteriaCaptor.capture(), eq(1));
        assertEquals("R2026", criteriaCaptor.getValue().getReservationNumber());
    }

    /** Confirms the first page renders a three-page window and a direct final-page link. */
    @Test
    void shouldRenderCompactPaginationWindowOnFirstPage() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 500));

        mockMvc.perform(get("/reservations").with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pagination__segment--current")))
                .andExpect(content().string(containsString("page=1")))
                .andExpect(content().string(containsString("page=2")))
                .andExpect(content().string(containsString(">...</span>")))
                .andExpect(content().string(containsString("page=49")))
                .andExpect(content().string(containsString("Next")))
                .andExpect(content().string(not(containsString("Previous"))));
    }

    /** Confirms a middle page has adjacent window pages, ellipsis, and the final page. */
    @Test
    void shouldRenderCompactPaginationWindowOnMiddlePage() throws Exception {
        when(reservationQueryService.findPage(any(), eq(9)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 10), 500));

        mockMvc.perform(get("/reservations").param("page", "9")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Previous")))
                .andExpect(content().string(containsString("page=8")))
                .andExpect(content().string(containsString("page=10")))
                .andExpect(content().string(containsString(">...</span>")))
                .andExpect(content().string(containsString("page=49")))
                .andExpect(content().string(containsString("Next")));
    }

    /** Confirms an adjacent final page is rendered directly without an unnecessary ellipsis. */
    @Test
    void shouldNotRenderEllipsisWhenFinalPageIsAdjacentToWindow() throws Exception {
        when(reservationQueryService.findPage(any(), eq(47)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(47, 10), 500));

        mockMvc.perform(get("/reservations").param("page", "47")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("page=46")))
                .andExpect(content().string(containsString(">48</span>")))
                .andExpect(content().string(containsString("page=48")))
                .andExpect(content().string(containsString("page=49")))
                .andExpect(content().string(not(containsString(">...</span>"))));
    }

    /** Confirms the final-page window has no duplicate final page, ellipsis, or Next action. */
    @Test
    void shouldRenderCompactPaginationWindowOnLastPage() throws Exception {
        when(reservationQueryService.findPage(any(), eq(49)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(49, 10), 500));

        mockMvc.perform(get("/reservations").param("page", "49")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Previous")))
                .andExpect(content().string(containsString(">48</a>")))
                .andExpect(content().string(containsString(">49</a>")))
                .andExpect(content().string(containsString(">50</span>")))
                .andExpect(content().string(not(containsString(">...</span>"))))
                .andExpect(content().string(not(containsString("Next"))));
    }

    /** Confirms the Create form hides the OTA field for DIRECT and shows it for an OTA source after redisplay. */
    @Test
    void shouldToggleOtaBookingReferenceFieldByRedisplayedSource() throws Exception {
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of());
        when(roomQueryService.findAllForReservationCreation()).thenReturn(List.of());

        mockMvc.perform(get("/reservations/new")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-ota-booking-reference-field")))
                .andExpect(content().string(containsString("hidden=\"hidden\"")));

        mockMvc.perform(post("/reservations")
                        .param("source", "AGODA")
                        .with(user("manager").authorities(manageBookingAndViewAuthorities()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OTA Booking Reference is required for this source.")))
                .andExpect(content().string(not(containsString("hidden=\"hidden\""))));
    }

    /** Confirms Reservation Detail shows human-friendly Source and, only for an OTA source, the reference. */
    @Test
    void shouldRenderSourceAndConditionalOtaBookingReferenceOnDetail() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.AGODA,
                "123456789",
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                BigDecimal.TEN,
                "VND",
                null,
                List.of()));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Agoda")))
                .andExpect(content().string(containsString("Ref: 123456789")))
                .andExpect(content().string(containsString("123456789")));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Direct")))
                .andExpect(content().string(not(containsString("Ref:"))));
    }

    /** Confirms the Reservation list renders all ten filters in the required three-column layout. */
    @Test
    void shouldRenderTenFiltersInThreeColumnLayout() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/reservations").with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"reservationNumber\"")))
                .andExpect(content().string(containsString("id=\"guest\"")))
                .andExpect(content().string(containsString("id=\"room\"")))
                .andExpect(content().string(containsString("id=\"source\"")))
                .andExpect(content().string(containsString("id=\"otaBookingReference\"")))
                .andExpect(content().string(containsString("id=\"status\"")))
                .andExpect(content().string(containsString("id=\"checkInFrom\"")))
                .andExpect(content().string(containsString("id=\"checkInTo\"")))
                .andExpect(content().string(containsString("id=\"checkOutFrom\"")))
                .andExpect(content().string(containsString("id=\"checkOutTo\"")))
                .andExpect(content().string(containsString("All sources")))
                .andExpect(content().string(containsString(">Agoda<")))
                .andExpect(content().string(containsString(">Booking.com<")))
                .andExpect(content().string(containsString(">Airbnb<")))
                .andExpect(content().string(not(containsString(">BOOKING_COM<"))));
    }

    /** Confirms the Reservation list table exposes the new Guest, Room, Source, and OTA columns. */
    @Test
    void shouldRenderGuestRoomSourceAndOtaColumnsInReservationList() throws Exception {
        when(reservationQueryService.findPage(any(), eq(0))).thenReturn(new PageImpl<>(
                List.of(
                        new ReservationSummaryResponse(
                                RESERVATION_ID,
                                "R20260911-000001",
                                "Nguyen Van A",
                                "201, 202",
                                "CONFIRMED",
                                com.example.hotel.entity.booking.BookingSource.BOOKING_COM,
                                "BK-987654",
                                LocalDate.of(2026, 9, 11),
                                LocalDate.of(2026, 9, 12),
                                BigDecimal.TEN,
                                "VND"),
                        new ReservationSummaryResponse(
                                UUID.randomUUID(),
                                "R20260911-000002",
                                "Tran Van B",
                                "301",
                                "DRAFT",
                                com.example.hotel.entity.booking.BookingSource.DIRECT,
                                null,
                                LocalDate.of(2026, 9, 13),
                                LocalDate.of(2026, 9, 14),
                                BigDecimal.ONE,
                                "VND")),
                PageRequest.of(0, 10),
                2));

        mockMvc.perform(get("/reservations").with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("201, 202")))
                .andExpect(content().string(containsString("Booking.com")))
                .andExpect(content().string(containsString("BK-987654")))
                .andExpect(content().string(containsString("Tran Van B")))
                .andExpect(content().string(containsString("301")))
                .andExpect(content().string(containsString("Direct")))
                .andExpect(content().string(containsString(">—<")));
    }

    /** Confirms Reservation list filters are preserved with the four new filters in pagination links. */
    @Test
    void shouldPreserveNewFiltersInPaginationLinks() throws Exception {
        when(reservationQueryService.findPage(any(), eq(1)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 23));

        mockMvc.perform(get("/reservations")
                        .param("guest", "Nguyen")
                        .param("room", "201")
                        .param("source", "AGODA")
                        .param("otaBookingReference", "123")
                        .param("page", "1")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("guest=Nguyen")))
                .andExpect(content().string(containsString("room=201")))
                .andExpect(content().string(containsString("source=AGODA")))
                .andExpect(content().string(containsString("otaBookingReference=123")));

        ArgumentCaptor<ReservationSearchCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(ReservationSearchCriteria.class);
        verify(reservationQueryService).findPage(criteriaCaptor.capture(), eq(1));
        assertEquals("Nguyen", criteriaCaptor.getValue().getGuest());
        assertEquals("201", criteriaCaptor.getValue().getRoom());
        assertEquals(com.example.hotel.entity.booking.BookingSource.AGODA, criteriaCaptor.getValue().getSource());
        assertEquals("123", criteriaCaptor.getValue().getOtaBookingReference());
    }

    /** Confirms the redesigned Reservation Information card renders all five tiles and a full-width Notes row. */
    @Test
    void shouldRenderReservationInformationTilesAndFullWidthNotes() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CONFIRMED"));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"reservation-info-grid\"")))
                .andExpect(content().string(containsString(">Guest<")))
                .andExpect(content().string(containsString(">Source<")))
                .andExpect(content().string(containsString(">Check-in<")))
                .andExpect(content().string(containsString(">Check-out<")))
                .andExpect(content().string(containsString(">Total<")))
                .andExpect(content().string(containsString("class=\"reservation-info-item reservation-info-notes\"")))
                .andExpect(content().string(containsString(">Notes<")));
    }

    /** Confirms the Assigned Rooms header uses correct singular and plural room-count wording. */
    @Test
    void shouldUseSingularAndPluralRoomCountWording() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20260911-000001", UUID.randomUUID(), "GUEST-001", "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), BigDecimal.TEN, "VND", null,
                List.of(new ReservationRoomResponse(
                        roomId, "101", LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12),
                        BigDecimal.TEN, BigDecimal.TEN))));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">1</strong>")))
                .andExpect(content().string(containsString(">room<")));

        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20260911-000001", UUID.randomUUID(), "GUEST-001", "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), BigDecimal.TEN, "VND", null,
                List.of(
                        new ReservationRoomResponse(roomId, "101", LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), BigDecimal.ONE, BigDecimal.ONE),
                        new ReservationRoomResponse(UUID.randomUUID(), "102", LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), BigDecimal.ONE, BigDecimal.ONE))));
        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">2</strong>")))
                .andExpect(content().string(containsString(">rooms<")));
    }

    /** Confirms Confirm never renders twice: not in the header, only once in Available Actions for DRAFT. */
    @Test
    void shouldNotDuplicateConfirmActionBetweenHeaderAndAvailableActions() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("DRAFT"));

        String body = mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("manager").authorities(manageBookingAndViewAuthorities())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int headerStart = body.indexOf("<div class=\"page-header\">");
        int headerEnd = body.indexOf("<div class=\"reservation-detail\">");
        String header = body.substring(headerStart, headerEnd);
        assertEquals(false, header.contains("type=\"submit\">Confirm<"));
        int confirmFormCount = body.split("action=\"/reservations/" + RESERVATION_ID + "/confirm\"", -1).length - 1;
        assertEquals(1, confirmFormCount);
    }

    /** Confirms the Guest Information summary links to Guest Detail only when the user can manage guests. */
    @Test
    void shouldLinkGuestSummaryOnlyWhenAuthorizedToManageGuests() throws Exception {
        UUID guestId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20260911-000001", guestId, "GUEST-001", "CONFIRMED",
                com.example.hotel.entity.booking.BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), BigDecimal.TEN, "VND", null, List.of()));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("admin").authorities(
                                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                                new SimpleGrantedAuthority("PERM_MANAGE_GUEST"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "class=\"guest-summary\" href=\"/guests/" + guestId + "\"")));

        mockMvc.perform(get("/reservations/{id}", RESERVATION_ID)
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/guests/" + guestId + "\""))))
                .andExpect(content().string(containsString("class=\"guest-summary\"")))
                .andExpect(content().string(containsString("GUEST-001")));
    }

    /** Confirms invalid date ranges render safely without executing an unrestricted query. */
    @Test
    void shouldRejectReservationDateRangeLongerThanOneCalendarYear() throws Exception {
        mockMvc.perform(get("/reservations")
                        .param("checkInFrom", "2026-01-01")
                        .param("checkInTo", "2027-01-02")
                        .with(user("viewer").authorities(viewBookingAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Check-in date range cannot exceed one calendar year.")))
                .andExpect(content().string(containsString("No reservations match the current filters.")))
                .andExpect(content().string(not(containsString("Reservation pages"))));

        verifyNoInteractions(reservationQueryService);
    }

    /** Supplies the checked-in Reservation, Stay, and authoritative balance required by detail rendering. */
    private void stubCheckedInReservation(BigDecimal outstanding) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CHECKED_IN",
                com.example.hotel.entity.booking.BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                BigDecimal.TEN,
                "JPY",
                null,
                List.of()));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(new StayResponse(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "CHECKED_IN",
                Instant.parse("2026-09-11T10:00:00Z"),
                null));
        when(stayBalanceService.calculate(UUID.fromString("22222222-2222-2222-2222-222222222222")))
                .thenReturn(new StayBalance(BigDecimal.TEN, BigDecimal.TEN.subtract(outstanding), outstanding));
    }

    /** Creates Reservation context with the requested status for confirmation metadata rendering. */
    private ReservationDetailResponse reservation(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                status,
                com.example.hotel.entity.booking.BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                BigDecimal.TEN,
                "VND",
                null,
                List.of());
    }

    /**
     * Supplies the three exact role authority sets that must read reservations.
     *
     * @return representative users and their effective authorities
     */
    private static Stream<Arguments> reservationViewUsers() {
        return Stream.of(
                Arguments.of("admin", manageBookingAndViewAuthorities()),
                Arguments.of("manager", manageBookingAndViewAuthorities()),
                Arguments.of("staff", viewBookingAuthority()));
    }

    /**
     * Supplies the role authority sets that retain the existing MANAGE_BOOKING write permission.
     *
     * @return representative administrative users and their effective authorities
     */
    private static Stream<Arguments> reservationManagers() {
        return Stream.of(
                Arguments.of("admin", manageBookingAndViewAuthorities()),
                Arguments.of("manager", manageBookingAndViewAuthorities()));
    }

    /**
     * Builds the authority used for reservation reads.
     *
     * @return the VIEW_BOOKING authority
     */
    private static List<SimpleGrantedAuthority> viewBookingAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /**
     * Builds the authorities shared by ADMIN and MANAGER for reservation operations.
     *
     * @return the MANAGE_BOOKING and VIEW_BOOKING authorities
     */
    private static List<SimpleGrantedAuthority> manageBookingAndViewAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
    }

    /**
     * Builds the complete authority set actually granted to ADMIN by the applied Flyway
     * migrations, for end-to-end sidebar navigation assertions.
     *
     * @return every authority ADMIN currently holds
     */
    private static List<SimpleGrantedAuthority> fullAdminAuthorities() {
        return Stream.of(
                        "MANAGE_BOOKING", "CHECK_IN", "MANAGE_USER", "MANAGE_ROOM", "MANAGE_PAYMENT",
                        "MANAGE_EXPENSE", "VIEW_BOOKING", "MANAGE_GUEST", "VIEW_REPORT", "CHECK_OUT",
                        "MANAGE_ADDITIONAL_REVENUE")
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
    }

    /**
     * Builds the complete authority set actually granted to MANAGER by the applied Flyway
     * migrations, for end-to-end sidebar navigation assertions.
     *
     * @return every authority MANAGER currently holds
     */
    private static List<SimpleGrantedAuthority> fullManagerAuthorities() {
        return Stream.of(
                        "MANAGE_BOOKING", "MANAGE_ROOM", "MANAGE_PAYMENT", "VIEW_REPORT", "VIEW_BOOKING",
                        "MANAGE_GUEST", "CHECK_OUT", "MANAGE_EXPENSE", "MANAGE_ADDITIONAL_REVENUE")
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
    }

    /**
     * Builds the complete authority set actually granted to STAFF by the applied Flyway
     * migrations (including the V20 Payment grant), for end-to-end sidebar navigation assertions.
     *
     * @return every authority STAFF currently holds
     */
    private static List<SimpleGrantedAuthority> fullStaffAuthorities() {
        return Stream.of("VIEW_BOOKING", "CHECK_IN", "CHECK_OUT", "MANAGE_PAYMENT")
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
    }

    /** Builds the authority set required to view reservation detail and detailed Folio access. */
    private static List<SimpleGrantedAuthority> viewAndManagePaymentAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    /** Builds the authority set required for reservation detail and check-out readiness. */
    private static List<SimpleGrantedAuthority> viewAndCheckOutAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"),
                new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /**
     * Builds the authority reserved for the existing check-in operation.
     *
     * @return the CHECK_IN authority
     */
    private static List<SimpleGrantedAuthority> checkInAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    /**
     * Builds the authority reserved for the approved check-out operation.
     *
     * @return the CHECK_OUT authority
     */
    private static List<SimpleGrantedAuthority> checkOutAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHECK_OUT"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
