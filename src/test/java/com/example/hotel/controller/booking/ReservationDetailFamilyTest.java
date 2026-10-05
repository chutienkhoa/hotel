package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.FolioReconciliationResponse;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailEligibility;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.RoomHistoryLineResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.CancellationReasonCode;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationDetailEligibilityService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayChargeBreakdown;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the shared state-aware Reservation Detail (Task33 spec 9.3.4): one shell for all six states, with
 * state/permission/eligibility-dependent actions, the room semantics of each state, the multi-room action rule,
 * the single Folio call-to-action, the financial summary and prepayment navigation.
 */
@WebMvcTest(ReservationPageController.class)
@Import(ReservationDetailFamilyTest.MethodSecurityTestConfiguration.class)
class ReservationDetailFamilyTest {

    private static final UUID RESERVATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID GUEST_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID STAY_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ROOM_201 = UUID.fromString("20100000-0000-0000-0000-000000000201");
    private static final UUID ROOM_202 = UUID.fromString("20200000-0000-0000-0000-000000000202");
    private static final UUID ROOM_305 = UUID.fromString("30500000-0000-0000-0000-000000000305");

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
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private ReservationDetailEligibilityService detailEligibilityService;

    @MockitoBean
    private JwtService jwtService;

    // ------------------------------------------------------------------------------------------ state actions

    /**
     * Confirms a DRAFT shows Cancel Reservation and Confirm directly in the header (no More menu), Edit in the
     * Reservation Information card, and Booking Contact / Notes edited from their own cards.
     */
    @Test
    void shouldOfferDraftActionsDirectlyInTheHeader() throws Exception {
        stub("DRAFT", List.of(bookedRoom(ROOM_201, "201")));

        String body = page(manager());

        assertContains(body, "id=\"cancel-reservation\"", "class=\"button button-danger\" data-detail-dialog=\"cancel-dialog\"",
                "id=\"confirm-reservation\"", "id=\"cancel-dialog\"", "id=\"edit-reservation\"",
                "/reservations/" + RESERVATION_ID + "/edit\"", "id=\"edit-booking-contact-card\"", "id=\"edit-notes-card\"");
        assertTrue(body.indexOf("id=\"cancel-reservation\"") < body.indexOf("id=\"confirm-reservation\""),
                "header order is Cancel Reservation, Confirm Reservation");
        assertAbsent(body, "id=\"reservation-more\"", "id=\"edit-booking-contact\"", "id=\"edit-notes\"",
                "id=\"check-in-reservation\"", "id=\"mark-no-show\"", "id=\"change-reservation-dates\"",
                "id=\"edit-guest-composition\"", "id=\"correct-ota-reference\"", "id=\"reassign-room\"");
    }

    /** Confirms CONFIRMED offers the narrow operations (never a generic Edit Reservation) when eligible. */
    @Test
    void shouldOfferConfirmedNarrowOperationsWithoutGenericEdit() throws Exception {
        stubSource("CONFIRMED", BookingSource.AGODA, List.of(bookedRoom(ROOM_201, "201")));
        eligibility(true, true);

        String body = page(everything());

        assertContains(body, "id=\"check-in-reservation\"", "id=\"reservation-more\"", "id=\"change-reservation-dates\"",
                "id=\"reassign-room\"", "id=\"edit-guest-composition\"", "id=\"correct-ota-reference\"",
                "id=\"mark-no-show\"", "id=\"cancel-reservation\"", "id=\"edit-booking-contact-card\"",
                "id=\"edit-notes-card\"");
        assertAbsent(body, "id=\"edit-reservation\"", "id=\"confirm-reservation\"", "id=\"edit-booking-contact\"",
                "id=\"edit-notes\"");
    }

    /** Confirms Correct OTA Reference is hidden for a DIRECT reservation. */
    @Test
    void shouldHideOtaCorrectionForDirectReservations() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));

        assertAbsent(page(everything()), "id=\"correct-ota-reference\"");
    }

    /** Confirms action visibility respects eligibility: no Check In before arrival, no No-show before the date passes. */
    @Test
    void shouldHideCheckInAndNoShowWhenTheBackendWouldReject() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        eligibility(false, false);

        String body = page(everything());

        assertAbsent(body, "id=\"check-in-reservation\"", "id=\"mark-no-show\"", "id=\"no-show-dialog\"");
        assertContains(body, "id=\"cancel-reservation\"");
    }

    /** Confirms action visibility respects permission: Check In needs CHECK_IN and the Reassign entry needs CHECK_IN. */
    @Test
    void shouldHideCheckInAndReassignWithoutCheckInPermission() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        eligibility(true, true);

        String body = page(user("m").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING")));

        assertAbsent(body, "id=\"check-in-reservation\"", "id=\"reassign-room\"");
        assertContains(body, "id=\"change-reservation-dates\"");
    }

    /** Confirms Cancel stays offered even when an active prepayment exists (the backend rejects it with a dialog). */
    @Test
    void shouldKeepCancelVisibleWhenAnActivePrepaymentExists() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(prepaymentSummary("1000000"));

        assertContains(page(everything()), "id=\"cancel-reservation\"", "id=\"prepayment-active\"");
    }

    /**
     * Confirms CHECKED_IN shows the four operational actions in order, no More menu, and Booking Contact / Notes edited
     * from their own cards.
     */
    @Test
    void shouldOfferCheckedInOperationalActions() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));

        String body = page(everything());

        assertContains(body, "id=\"reservation-change-room\"", "id=\"extend-stay\"", "id=\"reservation-folio-link\"",
                "id=\"reservation-checkout\"", "id=\"edit-booking-contact-card\"", "id=\"edit-notes-card\"");
        assertTrue(body.indexOf("id=\"reservation-change-room\"") < body.indexOf("id=\"extend-stay\"")
                && body.indexOf("id=\"extend-stay\"") < body.indexOf("id=\"reservation-folio-link\"")
                && body.indexOf("id=\"reservation-folio-link\"") < body.indexOf("id=\"reservation-checkout\""),
                "header order is Change Room, Extend Stay, Folio, Checkout");
        assertAbsent(body, "id=\"reservation-more\"", "id=\"edit-booking-contact\"", "id=\"edit-notes\"",
                "id=\"cancel-reservation\"", "id=\"mark-no-show\"", "id=\"check-in-reservation\"",
                "id=\"edit-reservation\"");
    }

    /** Confirms the CHECKED_IN tab bar is Overview, Room History, Notes, Audit Log with no Folio or Payments tab. */
    @Test
    void shouldNotOfferFolioOrPaymentsTabsWhileCheckedIn() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));

        String body = page(everything());

        assertContains(body, "id=\"tab-overview\"", "id=\"tab-room-history\"", "id=\"tab-notes\"",
                "id=\"tab-audit-log\"");
        assertAbsent(body, "id=\"tab-folio\"", "id=\"tab-payments\"");
    }

    /** Confirms the CHECKED_IN Folio action goes to the plain Folio route (Overview) and needs the existing Folio permission. */
    @Test
    void shouldOfferFolioActionOnlyWithManagePaymentAndOpenTheFolioOverview() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));

        String allowed = page(everything());
        String denied = page(user("c").authorities(authorities("PERM_VIEW_BOOKING", "PERM_CHECK_OUT",
                "PERM_MANAGE_BOOKING", "PERM_CHANGE_ROOM", "PERM_EXTEND_STAY")));

        assertContains(allowed, "id=\"reservation-folio-link\"", "href=\"/reservations/" + RESERVATION_ID + "/folio\"");
        assertAbsent(allowed, "id=\"reservation-add-charge\"", "addCharge=true", "folio?tab=");
        assertAbsent(denied, "id=\"reservation-folio-link\"");
        assertContains(denied, "id=\"reservation-checkout\"", "id=\"extend-stay\"");
    }

    /** Confirms every terminal state is immutable: no More menu, no dialogs, no edit controls. */
    @Test
    void shouldKeepTerminalStatesImmutable() throws Exception {
        for (String status : List.of("CHECKED_OUT", "CANCELLED", "NO_SHOW")) {
            if ("CHECKED_OUT".equals(status)) {
                stubStay(status, List.of());
            } else {
                stub(status, List.of(bookedRoom(ROOM_201, "201")));
            }
            String body = page(everything());

            assertAbsent(body, "id=\"reservation-more\"", "id=\"cancel-dialog\"", "id=\"no-show-dialog\"",
                    "id=\"confirm-reservation\"", "id=\"check-in-reservation\"", "id=\"reservation-checkout\"",
                    "id=\"extend-stay\"", "id=\"reservation-change-room\"", "id=\"edit-booking-contact-card\"",
                    "id=\"edit-notes-card\"", "id=\"edit-booking-contact\"", "id=\"edit-notes\"", "reassign-room",
                    "change-room-row");
            assertContains(body, "This reservation is closed.");
        }
    }

    /** Confirms the closed-state subtitle and state cards use the localized status, never the raw enum. */
    @Test
    void shouldShowLocalizedStatusLabels() throws Exception {
        stub("NO_SHOW", List.of(bookedRoom(ROOM_201, "201")));

        String english = page(everything());
        assertContains(english, "No Show", "No-show Information");
        assertAbsent(english, ">NO_SHOW<");

        stub("NO_SHOW", List.of(bookedRoom(ROOM_201, "201")));
        String vietnamese = mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(everything())
                        .cookie(new Cookie("pms-lang", "vi")))
                .andReturn().getResponse().getContentAsString();
        assertContains(vietnamese, "Thông tin không đến");
    }

    // ------------------------------------------------------------------------------------------ links

    /** Confirms Guest Code and Room Code are plain-text for a viewer and links only for users who may open them. */
    @Test
    void shouldLinkGuestAndRoomCodesOnlyWhenPermitted() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));

        String viewer = page(user("v").authorities(authorities("PERM_VIEW_BOOKING")));
        assertAbsent(viewer, "href=\"/guests/" + GUEST_ID + "\"", "href=\"/rooms/" + ROOM_201 + "\"");

        String admin = page(user("a").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_GUEST", "PERM_MANAGE_ROOM")));
        assertContains(admin, "href=\"/guests/" + GUEST_ID + "\"", "href=\"/rooms/" + ROOM_201 + "\"", "class=\"record-link\"");
    }

    // ------------------------------------------------------------------------------------------ multi-room rule

    /** Confirms one booked room is reassigned through the More menu, targeting exactly that room. */
    @Test
    void shouldReassignTheSingleBookedRoomFromTheMenu() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));

        String body = page(everything());

        assertContains(body, "id=\"reassign-room\"", "/check-in/reservations/" + RESERVATION_ID + "/rooms/" + ROOM_201
                + "/reassign?from=reservation");
        assertAbsent(body, "reassign-room-row");
    }

    /**
     * Regression: with several booked rooms the generic More action is omitted and every room row carries its own
     * Reassign, each targeting its own room. The page must never silently choose the first room.
     */
    @Test
    void shouldNotSilentlyChooseTheFirstRoomWhenSeveralRoomsAreBooked() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201"), bookedRoom(ROOM_202, "202")));

        String body = page(everything());

        assertAbsent(body, "id=\"reassign-room\"");
        assertEquals(2, count(body, "reassign-room-row\""), "one Reassign per booked room");
        assertContains(body,
                "/rooms/" + ROOM_201 + "/reassign?from=reservation", "/rooms/" + ROOM_202 + "/reassign?from=reservation");
    }

    /** Confirms the per-row Reassign needs CHECK_IN and is not rendered without it. */
    @Test
    void shouldHideRowReassignWithoutCheckInPermission() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201"), bookedRoom(ROOM_202, "202")));

        assertAbsent(page(user("m").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING"))),
                "reassign-room-row");
    }

    /** Confirms exactly one current room puts Change Room in the header only, with no duplicate in the room card. */
    @Test
    void shouldShowSingleCurrentRoomChangeRoomOnlyInTheHeader() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));

        String body = page(everything());

        assertContains(body, "id=\"reservation-change-room\"");
        assertEquals(1, count(body, "/rooms/" + ROOM_201 + "/change"), "no second Change Room entry for one room");
        assertAbsent(body, "change-room-row", "room-details-change-room");
    }

    /** Confirms several current rooms omit the header Change Room and give each row its own, targeting its own room. */
    @Test
    void shouldGiveEachCurrentRoomItsOwnChangeRoomAction() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201"), currentRoom(ROOM_202, "202")));

        String body = page(everything());

        assertAbsent(body, "id=\"reservation-change-room\"");
        assertEquals(2, count(body, "change-room-row\""));
        assertContains(body, "/rooms/" + ROOM_201 + "/change", "/rooms/" + ROOM_202 + "/change");
    }

    /** Confirms the summary strip stays compact for a large reservation: two codes, then +N and the room count. */
    @Test
    void shouldKeepTheSummaryStripBoundedForManyRooms() throws Exception {
        List<ReservationRoomResponse> rooms = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            rooms.add(bookedRoom(UUID.nameUUIDFromBytes(("room" + index).getBytes()), "30" + index));
        }
        stub("CONFIRMED", rooms);

        String body = page(everything());

        assertContains(body, "+4", "6 rooms");
        assertEquals(0, count(body, "rd-room-item\" aria-hidden"), "markup sanity");
        assertTrue(body.indexOf("303") < 0 || body.indexOf("303") > body.indexOf("id=\"booked-rooms\""),
                "only the first two codes appear in the strip");
    }

    // ------------------------------------------------------------------------------------------ room semantics

    /** Confirms booked rooms show the snapshot with room type, capacity and nights, and no stay-only sections. */
    @Test
    void shouldShowTheBookingSnapshotBeforeCheckIn() throws Exception {
        stub("CONFIRMED", List.of(new ReservationRoomResponse(ROOM_201, "201", LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 18), new BigDecimal("1200000"), new BigDecimal("2400000"), "Double Room", 2)));

        String body = page(everything());

        assertContains(body, "id=\"booked-rooms\"", "Booked Rooms", "Double Room", "2 adults", "1,200,000", "2,400,000");
        assertAbsent(body, "id=\"current-rooms\"", "id=\"stay-history\"", "id=\"tab-room-history\"");
    }

    /** Confirms cancelled and no-show reservations still show their booked rooms as the booking snapshot. */
    @Test
    void shouldShowBookedRoomsForCancelledAndNoShow() throws Exception {
        for (String status : List.of("CANCELLED", "NO_SHOW")) {
            stub(status, List.of(bookedRoom(ROOM_201, "201")));
            String body = page(everything());
            assertContains(body, "id=\"booked-rooms\"", "Booking Information");
            assertAbsent(body, "id=\"current-rooms\"", ">Stay Information<");
        }
    }

    /** Confirms the current-room rate follows the booked lineage even after a Room Change moved the stay to room 305. */
    @Test
    void shouldUseTheLineageRateNotARoomIdMatchForTheCurrentRoom() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_305, "305")));
        when(stayRoomAssignmentQueryService.findCurrentRoomRates(RESERVATION_ID))
                .thenReturn(Map.of(ROOM_305, new BigDecimal("1200000")));

        String body = page(everything());

        assertContains(body, "id=\"current-rooms\"", "1,200,000", "Assigned From", "Expected Check-out");
        assertAbsent(body, "id=\"booked-rooms\"");
    }

    /** Confirms CHECKED_OUT uses the stay history with exactly Room, Room Type, Assigned From and Assigned To. */
    @Test
    void shouldShowTheStayHistoryAfterCheckOutWithoutPerSegmentNights() throws Exception {
        stubStay("CHECKED_OUT", List.of());
        when(stayRoomAssignmentQueryService.findHistory(RESERVATION_ID)).thenReturn(List.of(
                new RoomHistoryLineResponse("201", Instant.parse("2026-09-16T07:00:00Z"),
                        Instant.parse("2026-09-17T03:00:00Z"), "Initial Check-in", "staff", ROOM_201, "Double Room", null),
                new RoomHistoryLineResponse("305", Instant.parse("2026-09-17T03:00:00Z"),
                        Instant.parse("2026-09-18T04:00:00Z"), "Guest request", "staff", ROOM_305, "Suite", "GUEST_REQUEST")));

        String body = page(everything());
        String section = body.substring(body.indexOf("id=\"stay-history\""));
        section = section.substring(0, section.indexOf("</section>"));

        assertContains(section, "Stay / Room History", "Room Type", "Assigned From", "Assigned To", "Double Room", "Suite");
        assertAbsent(section, "Nights");
        assertAbsent(body, "id=\"booked-rooms\"", "id=\"current-rooms\"");
    }

    /** Confirms the Room History tab localizes the change reason and never prints the raw enum. */
    @Test
    void shouldLocalizeRoomHistoryReasons() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_305, "305")));
        when(stayRoomAssignmentQueryService.findHistory(RESERVATION_ID)).thenReturn(List.of(
                new RoomHistoryLineResponse("305", Instant.parse("2026-09-17T03:00:00Z"), null, "Guest request", "staff",
                        ROOM_305, "Suite", "GUEST_REQUEST")));

        String body = page(everything());

        assertContains(body, "id=\"panel-room-history\"", "Guest request");
        assertAbsent(body, "GUEST_REQUEST");
    }

    // ------------------------------------------------------------------------------------------ financial boundary

    /** Confirms CHECKED_OUT has exactly one Open Folio call-to-action, in the header, never inside Financial Summary. */
    @Test
    void shouldShowOpenFolioOnceForCheckedOut() throws Exception {
        stubStay("CHECKED_OUT", List.of());
        stubBalance("3800000", "3800000");

        String body = page(everything());

        assertEquals(1, count(body, "id=\"reservation-folio\""));
        assertAbsent(body, "id=\"financial-open-folio\"");
        assertEquals(1, count(body, ">Open Folio<"));
    }

    /** Confirms CHECKED_IN has no Open Folio inside Financial Summary: the header Folio action is the only entry point. */
    @Test
    void shouldNotDuplicateFolioEntryInsideFinancialSummaryForCheckedIn() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));
        stubBalance("3600000", "2000000");

        String body = page(everything());

        assertContains(body, "id=\"financial-summary\"", "id=\"reservation-folio-link\"");
        assertAbsent(body, "id=\"financial-open-folio\"", "id=\"reservation-folio\"", ">Open Folio<");
        assertEquals(1, count(body, "/reservations/" + RESERVATION_ID + "/folio\""));
    }

    /** Confirms the Folio/Payments tabs, the money cards and Open Folio are absent without MANAGE_PAYMENT. */
    @Test
    void shouldHideFinancialNavigationWithoutManagePayment() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));
        stubBalance("3600000", "2000000");

        String body = page(user("c").authorities(authorities("PERM_VIEW_BOOKING", "PERM_CHECK_OUT")));

        assertAbsent(body, "id=\"tab-folio\"", "id=\"tab-payments\"", "id=\"financial-summary\"", "Open Folio",
                "/folio");
        assertContains(body, "id=\"tab-room-history\"");
    }

    /** Confirms Financial Summary splits Room Charges from Additional Charges and shows the existing payment figures. */
    @Test
    void shouldSplitRoomChargesFromAdditionalCharges() throws Exception {
        stubStay("CHECKED_IN", List.of(currentRoom(ROOM_201, "201")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("3950000"), new BigDecimal("2000000"), new BigDecimal("1950000")));
        when(stayBalanceService.chargeBreakdown(STAY_ID)).thenReturn(
                new StayChargeBreakdown(new BigDecimal("3600000"), new BigDecimal("350000")));
        when(folioReconciliationService.reconcile(STAY_ID)).thenReturn(reconciliation("MATCHED"));

        String body = page(everything());

        assertContains(body, "Room Charges", "3,600,000", "Additional Charges", "350,000", "2,000,000", "1,950,000");
        assertAbsent(body, "Remaining", "id=\"folio-indicator\"");
    }

    /** Confirms the folio indicator reads settled for a zero balance and "needs review" only when reconciliation flags it. */
    @Test
    void shouldShowTheFolioIndicatorOnlyWhereMeaningful() throws Exception {
        stubStay("CHECKED_OUT", List.of());
        stubBalance("3800000", "3800000");
        when(folioReconciliationService.reconcile(STAY_ID)).thenReturn(reconciliation("MATCHED"));
        String settled = page(everything());
        assertContains(settled, "Folio settled");
        assertAbsent(settled, "Folio needs review", "Matched", "MISMATCH");

        when(folioReconciliationService.reconcile(STAY_ID)).thenReturn(reconciliation("MISMATCH"));
        String review = page(everything());
        assertContains(review, "Folio needs review");
        assertAbsent(review, "Folio settled", "MISMATCH", "Matched");
    }

    /** Confirms CONFIRMED shows Prepaid and Reservation Total with navigation only: no Remaining, no refund/void forms. */
    @Test
    void shouldShowPrepaymentSummaryAndNavigationOnly() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(prepaymentSummary("1000000"));

        String body = page(everything());

        assertContains(body, "Prepayment Summary", "Prepaid", "Reservation Total", "id=\"view-prepayments\"",
                "/reservations/" + RESERVATION_ID + "/prepayments\"");
        assertAbsent(body, "Remaining", "/refund", "/void", "Refund Prepayment", "Void Prepayment");
    }

    /** Confirms the prepayment summary and View Prepayments need MANAGE_PAYMENT. */
    @Test
    void shouldHidePrepaymentNavigationWithoutManagePayment() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(prepaymentSummary("1000000"));

        assertAbsent(page(user("m").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING"))),
                "id=\"prepayments\"", "id=\"view-prepayments\"");
    }

    // ------------------------------------------------------------------------------------------ journey and lifecycle

    /** Confirms Recent Activity is newest first, capped at five entries, with View All opening the Audit Log tab. */
    @Test
    void shouldShowNewestFirstRecentActivityAndAuditLog() throws Exception {
        stub("CONFIRMED", List.of(bookedRoom(ROOM_201, "201")));
        List<ReservationActivityEntry> entries = new ArrayList<>();
        for (int hour = 0; hour < 7; hour++) {
            entries.add(new ReservationActivityEntry(Instant.parse("2026-09-16T0" + hour + ":00:00Z"), "u" + hour,
                    hour == 6 ? "CONFIRM" : "UPDATE_RESERVATION_NOTES"));
        }
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(entries);

        String body = page(everything());
        String recent = body.substring(body.indexOf("id=\"recent-activity\""));
        recent = recent.substring(0, recent.indexOf("</section>"));

        assertEquals(5, count(recent, "rd-timeline__item\""));
        assertTrue(recent.indexOf("Reservation confirmed") < recent.indexOf("Reservation notes updated"));
        assertContains(recent, "data-detail-tab-target=\"audit\"", "View All", "rd-timeline__dot--success");
        String audit = body.substring(body.indexOf("id=\"audit-log\""));
        assertTrue(audit.indexOf("u6") < audit.indexOf("u0"), "the Audit Log is newest first as well");
    }

    /** Confirms Recent Activity is shown for a DRAFT too, with its created/updated history. */
    @Test
    void shouldShowRecentActivityForDraft() throws Exception {
        stub("DRAFT", List.of(bookedRoom(ROOM_201, "201")));
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-16T01:00:00Z"), "admin", "CREATE")));

        assertContains(page(everything()), "id=\"recent-activity\"", "Reservation created");
    }

    /** Confirms lifecycle time and actor come from the audit history and nothing raw is exposed. */
    @Test
    void shouldDeriveLifecycleTimeAndActorFromTheAuditHistory() throws Exception {
        stub("CANCELLED", List.of(bookedRoom(ROOM_201, "201")));
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-16T02:00:00Z"), "front", "CONFIRM"),
                new ReservationActivityEntry(Instant.parse("2026-09-17T02:42:00Z"), "boss", "CANCEL")));

        String body = page(everything());

        assertContains(body, "Reservation cancelled on 17/09/2026 at 09:42 by boss.", "Cancelled By", "boss",
                "Confirmed At", "16/09/2026 09:00");
    }

    /** Confirms the cancellation reason shows as a localized label with its optional detail. */
    @Test
    void shouldShowTheCancellationReasonAsALocalizedLabel() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20260916-000001", GUEST_ID, "G-1", "CANCELLED", BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), 1, 0, BigDecimal.TEN, "VND", null,
                List.of(bookedRoom(ROOM_201, "201")), List.of(), null, null, null, false,
                CancellationReasonCode.PAYMENT_ISSUE, "Card declined twice", null, null, null, null));

        String body = page(everything());

        assertContains(body, "Payment issue", "Card declined twice");
        assertAbsent(body, "PAYMENT_ISSUE");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String page(RequestPostProcessor user) throws Exception {
        return mockMvc.perform(get("/reservations/{id}", RESERVATION_ID).with(user))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rd-header\"")))
                .andReturn().getResponse().getContentAsString();
    }

    private void stub(String status, List<ReservationRoomResponse> rooms) {
        stubSource(status, BookingSource.DIRECT, rooms);
    }

    private void stubSource(String status, BookingSource source, List<ReservationRoomResponse> rooms) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID, "R20260916-000001", GUEST_ID, "G-1", status, source,
                source == BookingSource.DIRECT ? null : "OTA-1", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18),
                1, 0, new BigDecimal("2400000"), "VND", null, rooms, List.of(), null, null, null, false, null, null,
                null));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(
                new GuestLookupResponse(GUEST_ID, "G-1", "Linh Bui", "linh@example.test", "+84912345678", "Vietnamese"));
        when(prepaymentService.summary(RESERVATION_ID)).thenReturn(prepaymentSummary("0"));
        eligibility(false, false);
    }

    private void stubStay(String status, List<CurrentRoomResponse> currentRooms) {
        stub(status, List.of());
        when(stayQueryService.existsByReservationId(RESERVATION_ID)).thenReturn(true);
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(new StayResponse(STAY_ID,
                status, Instant.parse("2026-09-16T07:00:00Z"),
                "CHECKED_OUT".equals(status) ? Instant.parse("2026-09-18T04:00:00Z") : null));
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(currentRooms);
        when(stayRoomAssignmentQueryService.findFinalRooms(RESERVATION_ID)).thenReturn(
                "CHECKED_OUT".equals(status) ? List.of(currentRoom(ROOM_201, "201")) : List.of());
        when(stayRoomAssignmentQueryService.findCurrentRoomRates(RESERVATION_ID))
                .thenReturn(Map.of(ROOM_201, new BigDecimal("1200000"), ROOM_202, new BigDecimal("1200000")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("2400000"), BigDecimal.ZERO, new BigDecimal("2400000")));
    }

    private void stubBalance(String charges, String payments) {
        BigDecimal totalCharges = new BigDecimal(charges);
        BigDecimal totalPayments = new BigDecimal(payments);
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(
                new StayBalance(totalCharges, totalPayments, totalCharges.subtract(totalPayments)));
        when(stayBalanceService.chargeBreakdown(STAY_ID)).thenReturn(new StayChargeBreakdown(totalCharges, BigDecimal.ZERO));
        when(folioReconciliationService.reconcile(STAY_ID)).thenReturn(reconciliation("MATCHED"));
    }

    private void eligibility(boolean checkIn, boolean noShow) {
        when(detailEligibilityService.evaluate(eq(RESERVATION_ID), eq(ReservationStatus.CONFIRMED), any()))
                .thenReturn(new ReservationDetailEligibility(checkIn, noShow));
    }

    private static ReservationRoomResponse bookedRoom(UUID roomId, String number) {
        return new ReservationRoomResponse(roomId, number, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18),
                new BigDecimal("1200000"), new BigDecimal("2400000"), "Double Room", 2);
    }

    private static CurrentRoomResponse currentRoom(UUID roomId, String number) {
        return new CurrentRoomResponse(UUID.randomUUID(), roomId, number, Instant.parse("2026-09-16T07:00:00Z"),
                "Double Room", 2);
    }

    private static PrepaymentSummaryResponse prepaymentSummary(String active) {
        return new PrepaymentSummaryResponse("VND", new BigDecimal("2400000"), new BigDecimal(active), BigDecimal.ZERO,
                new BigDecimal(active), new BigDecimal("2400000").subtract(new BigDecimal(active)), List.of());
    }

    private static FolioReconciliationResponse reconciliation(String status) {
        return new FolioReconciliationResponse(STAY_ID, status, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING"));
    }

    private static RequestPostProcessor everything() {
        return user("admin").authorities(authorities("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING", "PERM_MANAGE_GUEST",
                "PERM_MANAGE_ROOM", "PERM_CHECK_IN", "PERM_CHECK_OUT", "PERM_CHANGE_ROOM", "PERM_EXTEND_STAY",
                "PERM_MANAGE_PAYMENT"));
    }

    private static List<SimpleGrantedAuthority> authorities(String... names) {
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        for (String name : names) {
            list.add(new SimpleGrantedAuthority(name));
        }
        return list;
    }

    private static int count(String text, String fragment) {
        return text.split(Pattern.quote(fragment), -1).length - 1;
    }

    private static void assertContains(String body, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(body.contains(fragment), "expected to find: " + fragment);
        }
    }

    private static void assertAbsent(String body, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(!body.contains(fragment), "expected NOT to find: " + fragment);
        }
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
