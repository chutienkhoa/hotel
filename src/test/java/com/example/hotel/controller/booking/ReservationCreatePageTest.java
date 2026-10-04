package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the Task33 Create Reservation page: authorization, composition, the global error dialog with inline errors,
 * Save as Draft submission, the Create New Guest return, the on-demand Guest / Room lookups, i18n, and that Draft Edit
 * keeps its own template.
 */
@WebMvcTest({ReservationPageController.class, ReservationCreateLookupController.class})
@Import({ReservationCreatePageTest.TestConfig.class, I18nConfig.class})
class ReservationCreatePageTest {

    private static final UUID GUEST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
    private RoomAvailabilityService roomAvailability;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private StayRoomAssignmentQueryService stayRoomAssignmentQueryService;

    @MockitoBean
    private StayExtensionService stayExtensionService;

    @MockitoBean
    private ChargeService chargeService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private PrepaymentService prepaymentService;

    @MockitoBean
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private JwtService jwtService;

    // ------------------------------------------------------------------------------------------------ authorization

    /** Confirms both the page and its submission require MANAGE_BOOKING; view-only access is forbidden. */
    @Test
    void shouldRequireManageBookingForTheCreatePageAndSubmission() throws Exception {
        mockMvc.perform(get("/reservations/new").with(viewer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations").with(viewer()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(get("/reservations/new").with(manager())).andExpect(status().isOk());
        verify(reservationService, never()).create(any());
    }

    /** Confirms the on-demand Guest and Room lookups require MANAGE_BOOKING too. */
    @Test
    void shouldRequireManageBookingForTheLookups() throws Exception {
        mockMvc.perform(get("/reservations/new/guests").param("query", "mai").with(viewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reservations/new/rooms").param("checkInDate", "2026-10-20")
                        .param("checkOutDate", "2026-10-23").with(viewer()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------ composition

    /** Confirms the approved composition: header actions, five main cards in order, then the summary. */
    @Test
    void shouldRenderTheApprovedCompositionWithSaveAsDraftAsThePrimaryAction() throws Exception {
        String html = mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create a new reservation as a draft. You can confirm it later.")))
                .andExpect(content().string(containsString("Save as Draft")))
                .andExpect(content().string(containsString("form=\"reservation-create-form\"")))
                .andExpect(content().string(containsString("data-loading-text=\"Saving...\"")))
                .andExpect(content().string(containsString("data-submit-guard")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("Confirm Reservation"))))
                .andExpect(content().string(not(containsString("Create &amp; Confirm"))))
                .andReturn().getResponse().getContentAsString();

        int guest = html.indexOf("id=\"rc-guest\"");
        int stay = html.indexOf("id=\"rc-stay\"");
        int rooms = html.indexOf("id=\"rc-rooms\"");
        int contact = html.indexOf("id=\"rc-contact\"");
        int notes = html.indexOf("id=\"rc-notes\"");
        int summary = html.indexOf("data-summary");
        assertTrue(guest > 0 && guest < stay && stay < rooms && rooms < contact && contact < notes && notes < summary,
                "cards must follow the approved order");
    }

    /** Confirms the hotel date, the defaults and the VND-only money columns are rendered; no Currency selector. */
    @Test
    void shouldExposeHotelDateDefaultsAndVndColumns() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(containsString("data-hotel-today=\"2026-09-24\"")))
                .andExpect(content().string(containsString("data-default-adults=\"1\"")))
                .andExpect(content().string(containsString("data-default-children=\"0\"")))
                .andExpect(content().string(containsString("Nightly Rate (VND)")))
                .andExpect(content().string(containsString("Total (VND)")))
                .andExpect(content().string(containsString("name=\"currency\" type=\"hidden\" value=\"VND\"")))
                .andExpect(content().string(not(containsString("<select id=\"currency\""))))
                .andExpect(content().string(not(containsString("USD"))));
    }

    /** Confirms all four sources are offered with their enum labels in both languages, and the OTA field starts hidden. */
    @Test
    void shouldOfferTheFourSourcesWithLocalizedLabels() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(containsString("value=\"DIRECT\"")))
                .andExpect(content().string(containsString("value=\"AGODA\">Agoda")))
                .andExpect(content().string(containsString("value=\"BOOKING_COM\">Booking.com")))
                .andExpect(content().string(containsString("value=\"AIRBNB\">Airbnb")))
                .andExpect(content().string(containsString("data-ota-field")));
        mockMvc.perform(get("/reservations/new").with(manager()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Lưu nháp")))
                .andExpect(content().string(containsString("Chi tiết lưu trú")))
                .andExpect(content().string(containsString("Giá mỗi đêm (VND)")))
                .andExpect(content().string(containsString("Tóm tắt đặt phòng")));
    }

    /** Confirms every numeric control opts in to the shared numeric-only behaviour and none is a free-text money field. */
    @Test
    void shouldMakeEveryNumericFieldNumericOnlyThroughTheSharedHelper() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(containsString("/js/common/numeric-input.js")))
                .andExpect(content().string(containsString("id=\"adultCount\"")))
                .andExpect(content().string(containsString("data-numeric=\"integer\"")))
                .andExpect(content().string(containsString("data-numeric=\"vnd\" data-rate")))
                .andExpect(content().string(not(containsString("type=\"number\""))))
                .andExpect(content().string(not(containsString("js-money-input"))));
    }

    /** Confirms a non-numeric, decimal or negative count or rate never reaches the service (the server still validates). */
    @Test
    void shouldRejectNonNumericCountsAndRatesOnTheServer() throws Exception {
        mockMvc.perform(post("/reservations")
                        .param("guestId", GUEST_ID.toString())
                        .param("checkInDate", "2026-10-20")
                        .param("checkOutDate", "2026-10-23")
                        .param("adultCount", "2a")
                        .param("childCount", "-1")
                        .param("source", "DIRECT")
                        .param("currency", "VND")
                        .param("rooms[0].roomId", ROOM_ID.toString())
                        .param("rooms[0].nightlyRate", "1,000,000a")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter a whole number of adults.")))
                .andExpect(content().string(containsString("Children must be 0 or more.")))
                .andExpect(content().string(containsString("Enter a valid nightly rate.")));
        verify(reservationService, never()).create(any());
    }

    /** Confirms Booking Contact explains the whole-snapshot rule and Notes counts against the 5000 limit. */
    @Test
    void shouldExplainTheBookingContactRuleAndShowTheNotesLimit() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(containsString("they will be copied from the Primary Guest.")))
                .andExpect(content().string(containsString("not filled from the Primary Guest.")))
                .andExpect(content().string(containsString("never changes the Guest profile")))
                .andExpect(content().string(containsString("0 / 5000")))
                .andExpect(content().string(containsString("{0} / {1}")));
    }

    /** Confirms View Guest exists only for a user who may open Guest Detail. */
    @Test
    void shouldOfferViewGuestOnlyWithManageGuest() throws Exception {
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(not(containsString("data-guest-view"))))
                .andExpect(content().string(not(containsString("data-guest-url="))));
        mockMvc.perform(get("/reservations/new").with(managerWithGuestAccess()))
                .andExpect(content().string(containsString("data-guest-view")))
                .andExpect(content().string(containsString("data-guest-url=\"/guests/GUEST_ID\"")));
    }

    /** Confirms the selected Guest is displayed without ID / passport number or date of birth. */
    @Test
    void shouldRenderTheSelectedGuestWithoutSensitiveIdentityFields() throws Exception {
        GuestLookupResponse guest = guest();
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guest);

        mockMvc.perform(post("/reservations").param("guestId", GUEST_ID.toString()).with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-code=\"G000125\"")))
                .andExpect(content().string(containsString("data-phone=\"0901234567\"")))
                .andExpect(content().string(containsString("data-nationality=\"Vietnam\"")))
                .andExpect(content().string(not(containsString("1990-01-01"))))
                .andExpect(content().string(not(containsString("P1234567"))));
    }

    // --------------------------------------------------------------------------------------- Create New Guest return

    /** Confirms the Guest just created is pre-selected, announced inside the card, and not announced twice. */
    @Test
    void shouldPreselectTheGuestCreatedThroughTheRoundTrip() throws Exception {
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guest());

        mockMvc.perform(get("/reservations/new").flashAttr("createdGuestId", GUEST_ID)
                        .flashAttr("successMessage", "Guest created successfully.").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-created-guest-id=\"" + GUEST_ID + "\"")))
                .andExpect(content().string(containsString("data-guest-created-notice")))
                .andExpect(content().string(containsString("Guest created successfully and selected.")))
                .andExpect(content().string(containsString("value=\"" + GUEST_ID + "\"")))
                .andExpect(content().string(containsString("data-name=\"Nguyen Van A\"")))
                .andExpect(content().string(not(containsString("Guest created successfully."))));
    }

    /** Confirms an unknown created-guest id is ignored, and a later plain visit carries no created-guest marker. */
    @Test
    void shouldIgnoreAnUnknownCreatedGuestAndNotRestoreAnythingOnAPlainVisit() throws Exception {
        mockMvc.perform(get("/reservations/new").flashAttr("createdGuestId", UUID.randomUUID()).with(manager()))
                .andExpect(content().string(not(containsString("data-guest-created-notice"))))
                .andExpect(content().string(not(containsString("data-created-guest-id"))));
        mockMvc.perform(get("/reservations/new").with(manager()))
                .andExpect(content().string(not(containsString("data-created-guest-id"))))
                .andExpect(content().string(not(containsString("data-initial-guest"))));
    }

    // --------------------------------------------------------------------------------------------- validation UX

    /** Confirms a rejected submit shows the error dialog summary and inline errors, and no legacy header banner. */
    @Test
    void shouldShowTheErrorDialogSummaryAndInlineErrorsWithoutTheLegacyBanner() throws Exception {
        String html = mockMvc.perform(post("/reservations").with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unable to save reservation")))
                .andExpect(content().string(containsString("Please review the following information:")))
                .andExpect(content().string(containsString("<li>Primary Guest is required.</li>")))
                .andExpect(content().string(containsString("<li>Check-in date is required.</li>")))
                .andExpect(content().string(containsString("<li>Check-out date is required.</li>")))
                .andExpect(content().string(containsString("<li>Source is required.</li>")))
                .andExpect(content().string(containsString("Please select at least one room.")))
                .andExpect(content().string(containsString("id=\"guestId-error\">Primary Guest is required.")))
                .andExpect(content().string(containsString("id=\"checkInDate-error\">Check-in date is required.")))
                .andExpect(content().string(containsString("id=\"source-error\">Source is required.")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")))
                .andExpect(content().string(not(containsString("message message-error"))))
                .andExpect(content().string(not(containsString("Please correct the highlighted fields."))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.indexOf("<li>Primary Guest is required.</li>") < html.indexOf("<li>Source is required.</li>"),
                "the summary follows page order");
        verify(reservationService, never()).create(any());
    }

    /** Confirms the dialog summary and inline text are translated for the Vietnamese UI. */
    @Test
    void shouldTranslateTheErrorDialogForVietnamese() throws Exception {
        mockMvc.perform(post("/reservations").with(manager()).with(csrf()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Không thể lưu đặt phòng")))
                .andExpect(content().string(containsString("Vui lòng xem lại các thông tin sau:")))
                .andExpect(content().string(containsString("<li>Khách chính là bắt buộc.</li>")))
                .andExpect(content().string(containsString("<li>Nguồn là bắt buộc.</li>")));
    }

    /** Confirms a non-DIRECT source without a reference, and notes above 5000 characters, are rejected inline. */
    @Test
    void shouldRejectMissingOtaReferenceAndOverlongNotes() throws Exception {
        mockMvc.perform(post("/reservations").param("source", "AGODA").param("notes", "n".repeat(5001))
                        .with(manager()).with(csrf()))
                .andExpect(content().string(containsString("id=\"otaBookingReference-error\">OTA Booking Reference is required.")))
                .andExpect(content().string(containsString("id=\"notes-error\">Notes must not exceed 5000 characters.")))
                .andExpect(content().string(not(containsString("data-ota-field hidden"))));
    }

    /** Confirms a service rejection that belongs to a field is placed on that field and also listed in the dialog. */
    @Test
    void shouldMapAServiceRejectionToItsFieldAndTheDialog() throws Exception {
        when(reservationService.create(any())).thenThrow(new LocalizedResponseStatusException(HttpStatus.CONFLICT,
                "reservation.ota.error.duplicateIdentity", "duplicate", "AGODA", "123456789"));

        mockMvc.perform(validPost().with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "id=\"otaBookingReference-error\">OTA booking reference 123456789 is already used by another AGODA reservation.")))
                .andExpect(content().string(containsString("<li>OTA booking reference 123456789 is already used")))
                .andExpect(content().string(containsString("Unable to save reservation")));
    }

    /** Confirms a room-specific rejection marks that Room row, and a fractional VND rate names its room. */
    @Test
    void shouldMapRoomRowRejectionsToTheirRow() throws Exception {
        when(reservationService.create(any())).thenThrow(new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                "reservation.create.error.roomNotBookable", "not bookable", "105", ROOM_ID));
        mockMvc.perform(validPost().with(manager()).with(csrf()))
                .andExpect(content().string(containsString(
                        "data-error-room=\"Room 105 cannot be booked because it is inactive or out of service.\"")))
                .andExpect(content().string(containsString(
                        "<li>Room row 1: Room 105 cannot be booked because it is inactive or out of service.</li>")));

        doThrow(new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST,
                "reservation.create.error.nightlyRateScale", "scale", "105", ROOM_ID))
                .when(reservationService).create(any());
        mockMvc.perform(validPost().with(manager()).with(csrf()))
                .andExpect(content().string(containsString(
                        "data-error-rate=\"The nightly rate for room 105 must be a whole VND amount.\"")));
    }

    /** Confirms a rejection with no single field (an unknown Room) is shown in the dialog only, without a banner. */
    @Test
    void shouldShowAFormLevelRejectionInTheDialogOnly() throws Exception {
        when(reservationService.create(any())).thenThrow(new LocalizedResponseStatusException(HttpStatus.NOT_FOUND,
                "reservation.create.error.roomNotFound", "Room not found"));

        mockMvc.perform(validPost().with(manager()).with(csrf()))
                .andExpect(content().string(containsString("<li>One of the selected rooms could not be found.</li>")))
                .andExpect(content().string(not(containsString("message message-error"))))
                .andExpect(content().string(not(containsString("Room not found"))));
    }

    // ------------------------------------------------------------------------------------------------- submission

    /** Confirms a valid submit creates one DRAFT, keeps VND and redirects to the Detail with a success message. */
    @Test
    void shouldCreateTheDraftAndRedirectToTheDetail() throws Exception {
        when(reservationService.create(any()))
                .thenReturn(new Response(RESERVATION_ID, "R1", "DRAFT", BigDecimal.TEN, "VND"));

        mockMvc.perform(validPost().param("bookingContactName", "Mai").param("notes", "late").with(manager()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID))
                .andExpect(flash().attribute("successMessage", "Draft reservation created successfully."));

        ArgumentCaptor<CreateRequest> captor = ArgumentCaptor.forClass(CreateRequest.class);
        verify(reservationService).create(captor.capture());
        CreateRequest request = captor.getValue();
        assertEquals("VND", request.currency());
        assertEquals(new BigDecimal("1500000"), request.rooms().get(0).nightlyRate());
        assertEquals("Mai", request.bookingContactName());
        assertEquals(null, request.bookingContactPhone());
    }

    /** Confirms a crafted non-VND currency never reaches the service. */
    @Test
    void shouldRejectACraftedUsdCurrencyBeforeTheService() throws Exception {
        mockMvc.perform(validPost().param("currency", "USD").with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<li>Reservation currency must be VND.</li>")));
        verify(reservationService, never()).create(any());
    }

    // --------------------------------------------------------------------------------------------------- lookups

    /** Confirms the Guest search is narrowed to display fields: no date of birth, no ID / passport number. */
    @Test
    void shouldReturnGuestSearchResultsWithoutSensitiveFields() throws Exception {
        when(guestQueryService.searchForReservationCreation("mai")).thenReturn(List.of(new GuestLookupResponse(
                GUEST_ID, "G000125", "Nguyen Van A", "a@example.com", "0901234567", "Vietnam",
                LocalDate.of(1990, 1, 1), "P1234567")));

        mockMvc.perform(get("/reservations/new/guests").param("query", "mai").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(GUEST_ID.toString()))
                .andExpect(jsonPath("$[0].guestCode").value("G000125"))
                .andExpect(jsonPath("$[0].fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$[0].phone").value("0901234567"))
                .andExpect(jsonPath("$[0].dateOfBirth").doesNotExist())
                .andExpect(jsonPath("$[0].idDocumentNumber").doesNotExist());
    }

    /** Confirms the Room lookup is date-aware, carries Room Type and adult capacity, and validates the dates. */
    @Test
    void shouldReturnBookableRoomsForTheStayWithTypeAndCapacity() throws Exception {
        when(roomAvailability.bookableRoomsForPeriod(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 23)))
                .thenReturn(List.of(new RoomLookupResponse(ROOM_ID, "DEMO-201", "OCCUPIED", true, "Double Room", 2)));

        mockMvc.perform(get("/reservations/new/rooms").param("checkInDate", "2026-10-20")
                        .param("checkOutDate", "2026-10-23").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomNumber").value("DEMO-201"))
                .andExpect(jsonPath("$[0].roomTypeName").value("Double Room"))
                .andExpect(jsonPath("$[0].adultCapacity").value(2));
        mockMvc.perform(get("/reservations/new/rooms").param("checkInDate", "2026-10-23")
                        .param("checkOutDate", "2026-10-23").with(manager()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/reservations/new/rooms").param("checkInDate", "2026-10-23").with(manager()))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------------------------------- regression

    /** Confirms Draft Edit keeps its own template and is not forced through the Create page. */
    @Test
    void shouldKeepDraftEditOnItsOwnTemplate() throws Exception {
        when(reservationQueryService.findForEdit(RESERVATION_ID)).thenReturn(new ReservationEditResponse(
                RESERVATION_ID, "DRAFT", GUEST_ID, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), 2, 0,
                BookingSource.DIRECT, null, "VND", null,
                List.of(new ReservationRoomResponse(ROOM_ID, "101", LocalDate.of(2026, 9, 20),
                        LocalDate.of(2026, 9, 22), BigDecimal.TEN, BigDecimal.TEN)),
                List.of()));

        mockMvc.perform(get("/reservations/{id}/edit", RESERVATION_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit reservation")))
                .andExpect(content().string(containsString("Save Changes")))
                .andExpect(content().string(containsString("/reservations/" + RESERVATION_ID + "/edit")))
                .andExpect(content().string(not(containsString("reservation-create-form"))))
                .andExpect(content().string(not(containsString("create.js"))));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validPost() {
        return post("/reservations")
                .param("guestId", GUEST_ID.toString())
                .param("checkInDate", "2026-10-20")
                .param("checkOutDate", "2026-10-23")
                .param("adultCount", "2")
                .param("childCount", "0")
                .param("source", "DIRECT")
                .param("currency", "VND")
                .param("rooms[0].roomId", ROOM_ID.toString())
                .param("rooms[0].nightlyRate", "1500000");
    }

    private static GuestLookupResponse guest() {
        return new GuestLookupResponse(GUEST_ID, "G000125", "Nguyen Van A", "guest@example.com", "0901234567",
                "Vietnam", LocalDate.of(1990, 1, 1), "P1234567");
    }

    private static RequestPostProcessor manager() {
        return user("manager").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    private static RequestPostProcessor managerWithGuestAccess() {
        return user("manager").authorities(
                new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"), new SimpleGrantedAuthority("PERM_MANAGE_GUEST"));
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
