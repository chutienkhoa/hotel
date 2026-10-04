package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.CheckInRoomLine;
import com.example.hotel.dto.booking.response.OtaEntryReviewResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.WalkInRoomOption;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.exception.WalkInReviewException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the OTA Booking Not Entered four-step flow: the entry form, the pre-persistence Reservation Summary,
 * Create Reservation, and the POST/Redirect/GET contract that keeps browser Back, Forward and Refresh free of POSTs.
 */
@WebMvcTest(value = CheckInPageController.class, properties = "hotel.i18n.default-locale=en")
@Import({CheckInOtaEntryPageTest.MethodSecurityTestConfiguration.class, com.example.hotel.config.I18nConfig.class})
class CheckInOtaEntryPageTest {

    private static final UUID GUEST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROOM_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private com.example.hotel.service.room.RoomImageService roomImageService;

    @MockitoBean
    private JwtService jwtService;

    // ---- Entry form -------------------------------------------------------------------------------------------

    /** Confirms the entry form shows the four steps, OTA-only sources, a required reference, editable dates and no currency. */
    @Test
    void shouldRenderTheOtaEntryFormWithOtaSourcesOnlyAndNoCurrencySelector() throws Exception {
        stubGuests();
        String html = mockMvc.perform(get("/check-in/ota-entry").with(staff()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("OTA Booking (Not Entered) Reservation"));
        for (String step : List.of("Guest Information", "Stay Details", "Room Selection", "Reservation Summary")) {
            org.junit.jupiter.api.Assertions.assertTrue(html.contains(step), step);
        }
        for (String source : List.of("AGODA", "BOOKING_COM", "AIRBNB")) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    java.util.regex.Pattern.compile("name=\"source\" required type=\"radio\"\\s+value=\"" + source + "\"")
                            .matcher(html).find(),
                    source);
        }
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("value=\"DIRECT\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"otaBookingReference\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"checkInDate\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("name=\"currency\" type=\"hidden\" value=\"VND\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("id=\"currency\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Rate (VND / night)"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-availability-url=\"/check-in/ota-entry/available-rooms\""));
        // Guest identity fields come from the Guest record.
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-date-of-birth=\"20/04/1988\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-id-document-number=\"TZ1234567\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("data-nationality=\"Japan\""));
    }

    /** Confirms the entry form renders in Vietnamese. */
    @Test
    void shouldRenderTheOtaEntryFormInVietnamese() throws Exception {
        stubGuests();
        mockMvc.perform(get("/check-in/ota-entry").param("lang", "vi").with(staff()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đặt phòng OTA (chưa nhập)")))
                .andExpect(content().string(containsString("Nguồn đặt phòng (OTA)")))
                .andExpect(content().string(containsString("Mã đặt phòng OTA")))
                .andExpect(content().string(containsString("Kiểm tra phòng trống")))
                .andExpect(content().string(containsString("Giá (VND / đêm)")))
                .andExpect(content().string(containsString("Tiếp theo: Tóm tắt đặt phòng")));
    }

    // ---- Date-aware availability ----------------------------------------------------------------------------------

    /** Confirms the availability endpoint passes both OTA dates to the date-aware service path and returns its rooms. */
    @Test
    void shouldReturnDateAwareRoomOptionsForTheRequestedPeriod() throws Exception {
        when(checkInService.otaRoomOptions(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4)))
                .thenReturn(List.of(new WalkInRoomOption(ROOM_A, "201", "Deluxe", 2, "OCCUPIED")));

        mockMvc.perform(get("/check-in/ota-entry/available-rooms")
                        .param("checkInDate", "2026-12-01").param("checkOutDate", "2026-12-04").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomNumber").value("201"))
                .andExpect(jsonPath("$[0].adultCapacity").value(2));
        verify(checkInService, never()).availableRoomsForWalkIn(any());
        verify(checkInService, never()).walkInRoomOptions(any());
    }

    // ---- Summary is pre-persistence ---------------------------------------------------------------------------------

    /** Confirms the POST that prepares the Summary redirects to a GET and neither renders nor creates anything. */
    @ParameterizedTest
    @ValueSource(strings = {"AGODA", "BOOKING_COM", "AIRBNB"})
    void shouldRedirectEachOtaSourceToTheSummaryWithoutCreating(String source) throws Exception {
        when(checkInService.reviewOtaEntry(any())).thenReturn(review(BookingSource.valueOf(source)));

        mockMvc.perform(otaPost("/check-in/ota-entry/review", "source", source))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry/review"))
                .andExpect(content().string(not(containsString("Reservation Summary"))));
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms the Summary is a GET page that shows OTA semantics, can be refreshed, and creates nothing. */
    @Test
    void shouldRenderTheSummaryFromPreparedStateAndCreateNothingOnRefreshBackOrForward() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any())).thenReturn(review(BookingSource.AGODA));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(otaPost("/check-in/ota-entry/review").param("notes", "Non-smoking room").session(session))
                .andExpect(status().is3xxRedirection());

        for (int i = 0; i < 2; i++) {
            String html = mockMvc.perform(getAs("/check-in/ota-entry/review", session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Reservation Summary"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Agoda"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("OTA Booking Reference"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("1234567890"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Sato Taro"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("20/04/1988"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("TZ1234567"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Japan"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Room DEMO-201 (3 nights × 1,000,000)"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Room DEMO-202 (3 nights × 1,200,000)"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Total (VND)"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Create Reservation"));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("Non-smoking room"));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("Actual check-in"));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("Additional Revenue"));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("Direct (Walk-in)"));
            // Browser Back to the form and Forward to the Summary: the form is repopulated from the session.
            mockMvc.perform(getAs("/check-in/ota-entry", session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Non-smoking room")))
                    .andExpect(content().string(containsString("1234567890")));
        }
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms Back and every Edit on the Summary are plain GET links to the OTA form and the CTA posts to create. */
    @Test
    void shouldRenderSummaryBackAndEditAsPlainLinks() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any())).thenReturn(review(BookingSource.BOOKING_COM));

        String html = submitAndFollow(otaPost("/check-in/ota-entry/review", "source", "BOOKING_COM"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<a href=\"/check-in/ota-entry\">OTA Reservation</a>"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"button button-secondary walk-in-edit\" href=\"/check-in/ota-entry\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("action=\"/check-in/ota-entry\""));
        org.junit.jupiter.api.Assertions.assertTrue(
                java.util.regex.Pattern.compile("name=\"source\"[^>]*value=\"BOOKING_COM\"").matcher(html).find());
        org.junit.jupiter.api.Assertions.assertTrue(
                java.util.regex.Pattern.compile("name=\"currency\"[^>]*value=\"VND\"").matcher(html).find());
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("ota-entry/back"));
    }

    /** Confirms the Summary renders in Vietnamese with the localized Create Reservation action. */
    @Test
    void shouldRenderSummaryInVietnamese() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any())).thenReturn(review(BookingSource.AIRBNB));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(otaPost("/check-in/ota-entry/review", "source", "AIRBNB").session(session))
                .andExpect(redirectedUrl("/check-in/ota-entry/review"));

        mockMvc.perform(get("/check-in/ota-entry/review").param("lang", "vi").session(session).with(staff()))
                .andExpect(content().string(containsString("Tóm tắt đặt phòng")))
                .andExpect(content().string(containsString("Mã đặt phòng OTA")))
                .andExpect(content().string(containsString("Tạo đặt phòng")))
                .andExpect(content().string(containsString("Phòng DEMO-201 (3 đêm × 1,000,000)")))
                .andExpect(content().string(containsString("Thông tin bổ sung")));
    }

    /** Confirms a Summary requested with no prepared state, or only an incomplete one, returns to the form with a notice. */
    @Test
    void shouldRedirectTheSummaryToTheFormWhenThereIsNoCompletePreparedState() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult redirected = mockMvc.perform(getAs("/check-in/ota-entry/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"))
                .andReturn();
        stubGuests();
        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttrs(redirected.getFlashMap()))
                .andExpect(content().string(containsString("The OTA reservation summary is no longer available.")));

        mockMvc.perform(post("/check-in/ota-entry/new-guest").session(session)
                        .param("adultCount", "2").param("childCount", "0").param("currency", "VND").with(staff()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(getAs("/check-in/ota-entry/review", session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"));
        verify(checkInService, never()).reviewOtaEntry(any());
    }

    // ---- Validation and domain rejections: POST / Redirect / GET ----------------------------------------------------

    /** Confirms a missing OTA reference is rejected on its field and redirected, not rendered from the POST. */
    @Test
    void shouldRedirectAMissingOtaReferenceBackToTheFormWithItsValues() throws Exception {
        stubGuests();
        MockHttpSession session = new MockHttpSession();
        MvcResult posted = mockMvc.perform(otaPost("/check-in/ota-entry/review", "otaBookingReference", "", "notes", "Keep me")
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"))
                .andExpect(content().string(not(containsString("OTA Booking Reference"))))
                .andReturn();

        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OTA Booking Reference is required.")))
                .andExpect(content().string(containsString("Keep me")))
                .andExpect(content().string(containsString(selectedGuestOption())));
        verify(checkInService, never()).reviewOtaEntry(any());
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms a selected room without a rate is rejected, redirected, and named by Room No. in the missing-fields dialog. */
    @Test
    void shouldRedirectAMissingRateAndListItByRoomNumberInTheFeedbackDialog() throws Exception {
        stubGuests();
        stubRoomNumbers();
        MockHttpSession session = new MockHttpSession();
        MvcResult posted = mockMvc.perform(otaPost("/check-in/ota-entry/review", "rooms[0].nightlyRate", "")
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"))
                .andReturn();

        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-rate-invalid=\"true\"")))
                .andExpect(content().string(containsString("Required information missing")))
                .andExpect(content().string(containsString("Please check the following fields.")))
                .andExpect(content().string(containsString("<li>Rate — DEMO-303</li>")))
                .andExpect(content().string(not(containsString("Please correct the highlighted fields."))));
        verify(checkInService, never()).reviewOtaEntry(any());
    }

    /** Confirms exactly one missing required field produces a one-item list and no generic banner. */
    @Test
    void shouldListASingleMissingRequiredFieldOnTheOtaForm() throws Exception {
        stubGuests();
        stubRoomNumbers();

        submitAndFollow(otaPost("/check-in/ota-entry/review", "adultCount", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<li>Adults</li>")))
                .andExpect(content().string(not(containsString("<li>Guest</li>"))))
                .andExpect(content().string(not(containsString("<li>Rate"))))
                .andExpect(content().string(not(containsString("Please correct the highlighted fields."))))
                .andExpect(content().string(containsString("aria-describedby=\"adultCount-error\"")))
                .andExpect(content().string(containsString("id=\"adultCount-error\"")));
        verify(checkInService, never()).reviewOtaEntry(any());
    }

    /**
     * Confirms every missing OTA required field is listed at once, in form order and in English, a rate per selected
     * room names its Room No., the entered values are kept, and the generic banner and the Summary are not reached.
     */
    @Test
    void shouldListEveryMissingOtaRequiredFieldInEnglishAndKeepEntries() throws Exception {
        stubGuests();
        stubRoomNumbers();

        String html = submitAndFollow(multiMissingOtaPost())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("id=\"feedback-dialog-title\">Required information missing</h2>"));
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("id=\"feedback-dialog-message\">Please check the following fields.</p>"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("feedback-dialog--validation"));
        List<String> expected = List.of(
                "<li>Guest</li>", "<li>Check-in Date</li>", "<li>Check-out Date</li>", "<li>Adults</li>", "<li>Children</li>",
                "<li>OTA Booking Source</li>", "<li>OTA Booking Reference</li>", "<li>Rate — DEMO-303</li>",
                "<li>Rate — DEMO-404</li>");
        int previous = -1;
        for (String item : expected) {
            int position = html.indexOf(item);
            org.junit.jupiter.api.Assertions.assertTrue(position > previous, item + " missing or out of order");
            previous = position;
        }
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("Please correct the highlighted fields."));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<li>Room Selection</li>"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Keep these notes"));
        // The inline messages stay for assistive technology but are visually hidden: no repeated noisy text.
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"field-error visually-hidden\" id=\"adultCount-error\""));
        verify(checkInService, never()).reviewOtaEntry(any());
    }

    /** Confirms the same missing-fields dialog is localized in Vietnamese, including the Room No. in the rate entries. */
    @Test
    void shouldListEveryMissingOtaRequiredFieldInVietnamese() throws Exception {
        stubGuests();
        stubRoomNumbers();
        MockHttpSession session = new MockHttpSession();
        MvcResult posted = mockMvc.perform(multiMissingOtaPost().session(session))
                .andExpect(status().is3xxRedirection()).andReturn();

        mockMvc.perform(getAs("/check-in/ota-entry", session).param("lang", "vi").flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thiếu thông tin bắt buộc")))
                .andExpect(content().string(containsString("Vui lòng kiểm tra các trường sau.")))
                .andExpect(content().string(containsString("<li>Khách</li>")))
                .andExpect(content().string(containsString("<li>Người lớn</li>")))
                .andExpect(content().string(containsString("<li>Nguồn OTA</li>")))
                .andExpect(content().string(containsString("<li>Mã đặt phòng OTA</li>")))
                .andExpect(content().string(containsString("<li>Giá phòng — DEMO-303</li>")))
                .andExpect(content().string(containsString("<li>Giá phòng — DEMO-404</li>")))
                .andExpect(content().string(not(containsString("Vui lòng sửa các trường được đánh dấu."))));
    }

    /** Confirms a domain rejection (capacity) is not turned into a missing-fields dialog and keeps the existing banner. */
    @Test
    void shouldNotTurnAnOtaDomainRejectionIntoAMissingFieldsDialog() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any())).thenThrow(new WalkInReviewException(
                WalkInReviewException.Reason.INSUFFICIENT_ADULT_CAPACITY, "capacity", 2, 1));

        submitAndFollow(otaPost("/check-in/ota-entry/review"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please correct the highlighted fields.")))
                .andExpect(content().string(containsString("id=\"feedback-dialog-list\" hidden=\"hidden\"")))
                .andExpect(content().string(not(containsString("<li>Adults</li>"))))
                .andExpect(content().string(not(containsString("id=\"feedback-dialog-title\">Required information missing</h2>"))));
    }

    /** Confirms a missing room selection is listed as Room Selection (and the page offers the labels to the browser check). */
    @Test
    void shouldListAMissingOtaRoomSelectionAndExposeLabelsForTheBrowserCheck() throws Exception {
        stubGuests();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequestBuilder noRooms = post("/check-in/ota-entry/review").with(staff()).with(csrf())
                .param("guestId", GUEST_ID.toString()).param("checkInDate", "2026-12-01")
                .param("checkOutDate", "2026-12-04").param("adultCount", "2").param("childCount", "0")
                .param("source", "AGODA").param("otaBookingReference", "1234567890").param("currency", "VND");
        MvcResult posted = mockMvc.perform(noRooms.session(session)).andExpect(status().is3xxRedirection()).andReturn();

        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttrs(posted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<li>Room Selection</li>")))
                .andExpect(content().string(containsString("data-invalid=\"true\"")))
                .andExpect(content().string(containsString("data-ota-booking-reference=\"OTA Booking Reference\"")))
                .andExpect(content().string(containsString("data-nightly-rate=\"Rate\"")));
    }

    /** Confirms a crafted DIRECT source reaches the service, which rejects it, and nothing is created. */
    @Test
    void shouldRejectACraftedDirectSourceWithoutCreating() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "An OTA source is required for OTA Booking Not Entered."));

        submitAndFollow(otaPost("/check-in/ota-entry/review", "source", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An OTA source is required for OTA Booking Not Entered.")));
        verify(checkInService, never()).createOtaEntry(any());
    }

    /** Confirms the room, capacity and period rejections of the review appear next to their fields after a redirect. */
    @Test
    void shouldShowReviewRejectionsOnTheFormAfterARedirect() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any()))
                .thenThrow(new WalkInReviewException(WalkInReviewException.Reason.ROOM_UNAVAILABLE, "gone", "DEMO-201"))
                .thenThrow(new WalkInReviewException(
                        WalkInReviewException.Reason.INSUFFICIENT_ADULT_CAPACITY, "cap", 3, 2))
                .thenThrow(new WalkInReviewException(WalkInReviewException.Reason.DUPLICATE_ROOM, "dup", "DEMO-201"));

        submitAndFollow(otaPost("/check-in/ota-entry/review"))
                .andExpect(content().string(containsString("Room DEMO-201 is not available for the whole stay.")));
        submitAndFollow(otaPost("/check-in/ota-entry/review"))
                .andExpect(content().string(containsString("Insufficient room capacity: 3 adults, but the selected rooms support only 2 adults.")));
        submitAndFollow(otaPost("/check-in/ota-entry/review"))
                .andExpect(content().string(containsString("Room DEMO-201 is selected more than once.")));
    }

    /** Confirms a duplicate OTA identity is shown on the reference field, localized, with all entries kept. */
    @Test
    void shouldShowADuplicateOtaIdentityOnTheReferenceField() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any())).thenThrow(new LocalizedResponseStatusException(
                HttpStatus.CONFLICT, "reservation.ota.error.duplicateIdentity", "dup", "AGODA", "1234567890"));

        submitAndFollow(otaPost("/check-in/ota-entry/review"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OTA booking reference 1234567890 is already used by another AGODA reservation.")))
                .andExpect(content().string(containsString("value=\"1234567890\"")));
    }

    // ---- Create Reservation ----------------------------------------------------------------------------------------

    /** Confirms Create Reservation creates and confirms once, flashes a localized message, and goes to Check-in Review. */
    @Test
    void shouldCreateAndContinueToTheCanonicalCheckInReview() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(checkInService.createOtaEntry(any()))
                .thenReturn(new Response(reservationId, "R1", "CONFIRMED", BigDecimal.TEN, "VND"));
        MockHttpSession session = new MockHttpSession();

        MvcResult result = mockMvc.perform(otaPost("/check-in/ota-entry").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/reservations/" + reservationId))
                .andReturn();

        org.junit.jupiter.api.Assertions.assertEquals(
                "Reservation created and confirmed. Continue with Check-in review below.",
                result.getFlashMap().get("successMessage"));
        verify(checkInService, times(1)).createOtaEntry(any());
        verify(checkInService, never()).confirmCheckIn(any());
        stubGuests();
        mockMvc.perform(getAs("/check-in/ota-entry/review", session))
                .andExpect(redirectedUrl("/check-in/ota-entry"));
    }

    /** Confirms the success message follows the active locale. */
    @Test
    void shouldLocalizeTheCreateSuccessMessage() throws Exception {
        when(checkInService.createOtaEntry(any()))
                .thenReturn(new Response(UUID.randomUUID(), "R1", "CONFIRMED", BigDecimal.TEN, "VND"));

        MvcResult result = mockMvc.perform(otaPost("/check-in/ota-entry").cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi")))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        org.junit.jupiter.api.Assertions.assertEquals(
                "Đã tạo và xác nhận đặt phòng. Tiếp tục với bước xem lại nhận phòng bên dưới.",
                result.getFlashMap().get("successMessage"));
    }

    /** Confirms a failure at the authoritative boundary redirects back to the form with entries kept and a message. */
    @Test
    void shouldReturnToTheFormWithEntriesKeptWhenCreateFails() throws Exception {
        stubGuests();
        when(checkInService.createOtaEntry(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Room is already booked for these dates"));
        MockHttpSession session = new MockHttpSession();

        MvcResult posted = mockMvc.perform(otaPost("/check-in/ota-entry").param("notes", "Keep me too").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-in/ota-entry"))
                .andReturn();

        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttrs(posted.getFlashMap()))
                .andExpect(content().string(containsString("Room is already booked for these dates")))
                .andExpect(content().string(containsString("Keep me too")))
                .andExpect(content().string(containsString("1234567890")));
        // Refreshing or navigating Back later still shows the entries, without the flash.
        mockMvc.perform(getAs("/check-in/ota-entry", session))
                .andExpect(content().string(containsString("Keep me too")));
    }

    /** Confirms a stale selection is reported on the form by the re-check before create, and nothing is created. */
    @Test
    void shouldReportAStaleSelectionBeforeCreating() throws Exception {
        stubGuests();
        when(checkInService.reviewOtaEntry(any()))
                .thenThrow(new WalkInReviewException(WalkInReviewException.Reason.ROOM_UNAVAILABLE, "gone", "DEMO-201"));

        submitAndFollow(otaPost("/check-in/ota-entry"))
                .andExpect(content().string(containsString("Room DEMO-201 is not available for the whole stay.")));
        verify(checkInService, never()).createOtaEntry(any());
    }

    // ---- Create New Guest ---------------------------------------------------------------------------------------------

    /** Confirms Create New Guest keeps every entry in a non-consuming session form and pre-selects the created Guest. */
    @Test
    void shouldKeepAllEntriesAcrossCreateNewGuestAndAfterRefresh() throws Exception {
        stubGuests();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(otaPost("/check-in/ota-entry/new-guest").session(session)
                        .param("guestId", "")
                        .param("rooms[1].roomId", ROOM_B.toString()).param("rooms[1].nightlyRate", "1200000")
                        .param("notes", "Round trip note"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/guests/new?returnTo=/check-in/ota-entry"));

        mockMvc.perform(getAs("/check-in/ota-entry", session).flashAttr("createdGuestId", GUEST_ID))
                .andExpect(content().string(containsString(selectedGuestOption())))
                .andExpect(content().string(containsString("Round trip note")))
                .andExpect(content().string(containsString("1234567890")))
                .andExpect(content().string(containsString("data-rate=\"1200000\"")))
                .andExpect(content().string(containsString("value=\"2026-12-01\"")));
        // The one-shot createdGuestId flash is gone on Refresh; the created Guest and every entry are still there.
        mockMvc.perform(getAs("/check-in/ota-entry", session))
                .andExpect(content().string(containsString(selectedGuestOption())))
                .andExpect(content().string(containsString("Round trip note")));
        verify(checkInService, never()).createOtaEntry(any());
        verify(checkInService, never()).reviewOtaEntry(any());
    }

    /** Confirms the landing, which abandons an in-progress flow, clears the in-progress OTA form. */
    @Test
    void shouldClearTheInProgressOtaFormWhenReturningToTheLanding() throws Exception {
        stubGuests();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(otaPost("/check-in/ota-entry/new-guest").session(session).param("notes", "Abandoned"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(getAs("/check-in", session)).andExpect(status().isOk());
        mockMvc.perform(getAs("/check-in/ota-entry", session))
                .andExpect(content().string(not(containsString("Abandoned"))));
    }

    // ---- Authorization ----------------------------------------------------------------------------------------------------

    /** Confirms every new OTA endpoint stays behind CHECK_IN. */
    @Test
    void shouldForbidTheOtaEndpointsWithoutCheckIn() throws Exception {
        var none = List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"));
        mockMvc.perform(get("/check-in/ota-entry/review").with(user("viewer").authorities(none)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/ota-entry/review").with(user("viewer").authorities(none)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/check-in/ota-entry/available-rooms")
                        .param("checkInDate", "2026-12-01").param("checkOutDate", "2026-12-04")
                        .with(user("viewer").authorities(none)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/check-in/ota-entry").with(user("viewer").authorities(none)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private void stubGuests() {
        GuestLookupResponse guest = guest();
        when(guestQueryService.findAllForReservationCreation()).thenReturn(List.of(guest));
        when(guestQueryService.findForReservationCreation(GUEST_ID)).thenReturn(guest);
    }

    private static GuestLookupResponse guest() {
        return new GuestLookupResponse(GUEST_ID, "G000001", "Sato Taro", "sato.taro@example.com", "+81 90 1234 5678",
                "Japan", LocalDate.of(1988, 4, 20), "TZ1234567");
    }

    private static String selectedGuestOption() {
        return "value=\"" + GUEST_ID + "\" data-guest-code=\"G000001\" data-full-name=\"Sato Taro\"";
    }

    private static OtaEntryReviewResponse review(BookingSource source) {
        LocalDate in = LocalDate.of(2026, 12, 1);
        LocalDate out = LocalDate.of(2026, 12, 4);
        return new OtaEntryReviewResponse(GUEST_ID, "Sato Taro", "G000001", false, null, source, "1234567890", in, out,
                List.of(line(ROOM_A, "DEMO-201", "1000000"), line(ROOM_B, "DEMO-202", "1200000")),
                new BigDecimal("6600000"), "VND");
    }

    private static CheckInRoomLine line(UUID roomId, String number, String rate) {
        BigDecimal nightly = new BigDecimal(rate);
        return new CheckInRoomLine(roomId, number, "Deluxe", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4), nightly, 3,
                nightly.multiply(BigDecimal.valueOf(3)), 2, "AVAILABLE");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    private MockHttpServletRequestBuilder getAs(String path, MockHttpSession session) {
        return get(path).session(session).with(staff());
    }

    /** A complete, valid OTA submission; individual tests override single parameters. */
    private void stubRoomNumbers() {
        com.example.hotel.dto.room.response.RoomResponse roomA = org.mockito.Mockito.mock(com.example.hotel.dto.room.response.RoomResponse.class);
        when(roomA.id()).thenReturn(ROOM_A);
        when(roomA.roomNumber()).thenReturn("DEMO-303");
        com.example.hotel.dto.room.response.RoomResponse roomB = org.mockito.Mockito.mock(com.example.hotel.dto.room.response.RoomResponse.class);
        when(roomB.id()).thenReturn(ROOM_B);
        when(roomB.roomNumber()).thenReturn("DEMO-404");
        when(roomQueryService.findAllByIds(any())).thenReturn(List.of(roomA, roomB));
    }

    /** Every required OTA field empty, plus two selected rooms with no rate each; notes are entered and must survive. */
    private MockHttpServletRequestBuilder multiMissingOtaPost() {
        return otaPost("/check-in/ota-entry/review",
                "guestId", "", "checkInDate", "", "checkOutDate", "", "adultCount", "", "childCount", "",
                "source", "", "otaBookingReference", "", "notes", "Keep these notes",
                "rooms[0].nightlyRate", "", "rooms[1].roomId", ROOM_B.toString(), "rooms[1].nightlyRate", "");
    }

    private MockHttpServletRequestBuilder otaPost(String path, String... overrides) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("guestId", GUEST_ID.toString());
        values.put("checkInDate", "2026-12-01");
        values.put("checkOutDate", "2026-12-04");
        values.put("adultCount", "2");
        values.put("childCount", "0");
        values.put("source", "AGODA");
        values.put("otaBookingReference", "1234567890");
        values.put("currency", "VND");
        values.put("rooms[0].roomId", ROOM_A.toString());
        values.put("rooms[0].nightlyRate", "1000000");
        for (int i = 0; i < overrides.length; i += 2) {
            values.put(overrides[i], overrides[i + 1]);
        }
        MockHttpServletRequestBuilder request = post(path).with(staff()).with(csrf());
        values.forEach(request::param);
        return request;
    }

    /** Posts, then performs the GET it redirects to with the same session and flash attributes. */
    private ResultActions submitAndFollow(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult posted = mockMvc.perform(request.session(session)).andExpect(status().is3xxRedirection()).andReturn();
        String location = posted.getResponse().getRedirectedUrl();
        org.junit.jupiter.api.Assertions.assertNotNull(location);
        MockHttpServletRequestBuilder follow = get(location).session(session).with(staff());
        if (!posted.getFlashMap().isEmpty()) {
            follow.flashAttrs(posted.getFlashMap());
        }
        return mockMvc.perform(follow);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
