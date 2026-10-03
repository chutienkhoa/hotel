package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayExtensionSummaryResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.StayBalance;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies Folio MVC authorization, section routing, financial rendering, and post-redirect-get behavior. */
@WebMvcTest(FolioPageController.class)
@Import({FolioPageControllerTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class FolioPageControllerTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STAY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String FOLIO_PATH = "/reservations/" + RESERVATION_ID + "/folio";

    private static Cookie language(String value) {
        return new Cookie("pms-lang", value);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private ChargeService chargeService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private StayRoomAssignmentQueryService stayRoomAssignmentQueryService;

    @MockitoBean
    private StayExtensionService stayExtensionService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms the Overview renders the authoritative financial summary and links to the Payments section. */
    @Test
    void shouldRenderOverviewFinancialSummaryForManagePayment() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(view().name("stay/folio"))
                .andExpect(content().string(containsString("Total Charges")))
                .andExpect(content().string(containsString("Total Payments")))
                .andExpect(content().string(containsString("Outstanding")))
                .andExpect(content().string(containsString("Outstanding Balance")))
                .andExpect(content().string(containsString("Stay Summary")))
                .andExpect(content().string(containsString("Recent Activity")))
                .andExpect(content().string(containsString("100 VND")))
                .andExpect(content().string(containsString("href=\"" + FOLIO_PATH + "?tab=payments\"")))
                .andExpect(content().string(containsString("href=\"" + FOLIO_PATH + "?tab=charges\"")))
                .andExpect(content().string(not(containsString(">Add Payment</span>"))))
                .andExpect(content().string(not(containsString(">Add Charge</span>"))))
                .andExpect(content().string(not(containsString("#add-charge"))))
                .andExpect(content().string(not(containsString("View all charges"))))
                .andExpect(content().string(not(containsString("Record Payment"))))
                .andExpect(content().string(not(containsString("Add Fixed Charge"))))
                .andExpect(content().string(not(containsString("Add as Pending"))));
    }

    /** Confirms the Charges section lists every Charge and offers the approved Add Charge forms. */
    @Test
    void shouldRenderChargesSectionWithAddChargeForms() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(view().name("stay/folio"))
                .andExpect(content().string(containsString("Total Charges")))
                .andExpect(content().string(containsString("Fixed Amount")))
                .andExpect(content().string(containsString("Itemized")))
                .andExpect(content().string(containsString("id=\"folio-charge-submit\"")))
                .andExpect(content().string(containsString("Room Charge")))
                .andExpect(content().string(containsString("1.5")))
                .andExpect(content().string(not(containsString("1.500000"))))
                .andExpect(content().string(containsString("100 VND")));
    }

    /** Confirms the Payments section lists every Payment and offers the Add Payment form. */
    @Test
    void shouldRenderPaymentsSectionWithAddPaymentForm() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Total Payments")))
                .andExpect(content().string(containsString("Mark paid")))
                .andExpect(content().string(containsString("Add as Pending")))
                .andExpect(content().string(containsString("Record Payment")));
    }

    /** Confirms Stay Summary dates follow the PMS date convention and overdue shows only for a CHECKED_IN stay. */
    @Test
    void shouldShowDateConventionAndOverdueInStaySummaryOnlyWhileCheckedIn() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");
        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("date-value--check-in")))
                .andExpect(content().string(containsString("date-value--check-out")))
                .andExpect(content().string(containsString("(3 days overdue)")));

        stubFolio("CHECKED_OUT", BigDecimal.ZERO, "PAID");
        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(content().string(not(containsString("overdue)"))));
    }

    /** Confirms the Folio currency is displayed and exposed to client-side JS via a data attribute. */
    @Test
    void shouldExposeFolioCurrencyToPageAndJs() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-reservation-currency=\"VND\"")))
                .andExpect(content().string(containsString("100 VND")));
    }

    /** Confirms Paid At renders as a compact local date-time instead of the raw ISO Instant. */
    @Test
    void shouldFormatPaidAtAsCompactLocalDateTimeInsteadOfRawInstant() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("2026-09-11T11:00:00Z"))))
                .andExpect(content().string(containsString("11/09/2026 18:00")));
    }

    /**
     * Confirms Actual Check-in and Charges &gt; Charged At use the same centralized
     * dd/MM/yyyy HH:mm standard, converted to the hotel's Asia/Ho_Chi_Minh display timezone,
     * with no raw ISO Instant rendered anywhere on the page.
     */
    @Test
    void shouldFormatActualCheckInAndChargedAtUsingHotelDisplayStandard() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("2026-09-11T10:00:00Z"))))
                .andExpect(content().string(containsString("11/09/2026 17:00")));
    }

    /** Confirms a cross-currency Payment renders its received, rate, and applied columns correctly. */
    @Test
    void shouldRenderReceivedRateAndAppliedForCrossCurrencyPayment() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(crossCurrencyPayment("PENDING")));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("120 USD")))
                .andExpect(content().string(containsString("1 USD = 25,000 VND")))
                .andExpect(content().string(containsString("3,000,000 VND")));
    }

    /** Confirms a same-currency Payment renders its Rate cell as an em dash, never a formatted rate. */
    @Test
    void shouldRenderDashForSameCurrencyPaymentRate() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("1 USD = 25,000"))));
    }

    /** Confirms the Refund dialog shows the original tender amount and currency, not appliedAmount. */
    @Test
    void shouldConfirmRefundUsingOriginalTenderAmount() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(crossCurrencyPayment("PAID")));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Refund amount")))
                .andExpect(content().string(containsString("120 USD")));
    }

    /** Confirms the Add Payment form offers both VND and USD regardless of Reservation currency. */
    @Test
    void shouldOfferBothCurrenciesInAddPaymentForm() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"VND\">VND</option>")))
                .andExpect(content().string(containsString("<option value=\"USD\">USD</option>")));
    }

    /** Confirms Payment lifecycle actions render only for the statuses that still permit a transition. */
    @Test
    void shouldRenderPaymentActionsOnlyForEligibleStatuses() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "FAILED");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Mark paid"))))
                .andExpect(content().string(not(containsString("Mark failed"))))
                .andExpect(content().string(not(containsString("payment-refund-reason"))));
    }

    /** Confirms an invalid Add Payment submission redisplays the Folio with the submitted values preserved. */
    @Test
    void shouldPreservePaymentFormValuesOnValidationErrorRedisplay() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments", RESERVATION_ID)
                        .param("amount", "120.00")
                        .param("currency", "USD")
                        .param("exchangeRate", "25000")
                        .param("reference", "Wire ref")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("stay/folio"))
                .andExpect(content().string(containsString("value=\"120.00\"")))
                .andExpect(content().string(containsString("value=\"25000\"")))
                .andExpect(content().string(containsString("value=\"USD\" selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"Wire ref\"")));
    }

    /**
     * Confirms Charge monetary fields (Fixed Amount, Unit Price) use the shared money-input
     * behavior consistent with Payment Amount/Exchange Rate, while Quantity — not a monetary
     * field — keeps its plain numeric input untouched.
     */
    @Test
    void shouldApplyMoneyInputToChargeMonetaryFieldsOnly() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<input class=\"js-money-input\" id=\"fixed-charge-amount\"")))
                .andExpect(content().string(containsString("id=\"fixed-charge-amount\" inputmode=\"decimal\"")))
                .andExpect(content().string(containsString(
                        "<input class=\"js-money-input\" id=\"itemized-charge-unit-price\"")))
                .andExpect(content().string(containsString(
                        "id=\"itemized-charge-unit-price\" inputmode=\"decimal\"")))
                .andExpect(content().string(containsString(
                        "<input id=\"itemized-charge-quantity\" inputmode=\"numeric\"")))
                .andExpect(content().string(not(containsString(
                        "class=\"js-money-input\" id=\"itemized-charge-quantity\""))));
    }

    /**
     * Confirms Quantity renders as a plain whole-number textbox: no monetary formatting, no
     * decimal step, and no native number-input spinner.
     */
    @Test
    void shouldRenderQuantityAsPlainWholeNumberTextInput() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<input id=\"itemized-charge-quantity\" inputmode=\"numeric\" pattern=\"[0-9]*\" required "
                                + "type=\"text\"")))
                .andExpect(content().string(not(containsString("id=\"itemized-charge-quantity\" min="))))
                .andExpect(content().string(not(containsString("id=\"itemized-charge-quantity\" type=\"number\""))));
    }

    /** Confirms the Add Charge type dropdown does not offer ROOM as a manually selectable type. */
    @Test
    void shouldNotOfferRoomAsManualChargeType() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"BREAKFAST\"")))
                .andExpect(content().string(not(containsString("value=\"ROOM\""))));
    }

    /** Confirms absent optional Charge pricing fields render as dashes rather than null money. */
    @Test
    void shouldRenderFixedChargeOptionalPricingFieldsAsDashes() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(fixedCharge()));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("80,000 VND")))
                .andExpect(content().string(containsString("<td class=\"table-number\">—</td>")))
                .andExpect(content().string(not(containsString("null VND"))));
    }

    /** Confirms itemized Charge pricing fields retain their formatted values. */
    @Test
    void shouldRenderItemizedChargePricingFields() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(itemizedCharge()));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3")))
                .andExpect(content().string(not(containsString("3.000000"))))
                .andExpect(content().string(containsString("30,000 VND")))
                .andExpect(content().string(containsString("90,000 VND")));
    }

    /** Confirms Refund and Void open from the Payment Detail panel as dialogs, never as controls inside table rows. */
    @Test
    void shouldRenderRefundAndVoidAsPaymentDetailDialogs() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment Detail")))
                .andExpect(content().string(containsString("data-payment-row=")))
                .andExpect(content().string(containsString("folio-refund-dialog-")))
                .andExpect(content().string(containsString("folio-payment-void-dialog-")))
                .andExpect(content().string(containsString("Original payment")))
                .andExpect(content().string(containsString("folio-record-payment-dialog")))
                .andExpect(content().string(not(containsString("Add Payment"))))
                .andExpect(content().string(not(containsString("table-actions"))));
    }

    /**
     * Confirms the Overview links to the existing Checkout Review route instead of posting check-out
     * directly, and offers that link only to a user who holds the check-out authority.
     */
    @Test
    void shouldLinkToCheckoutReviewInsteadOfPostingCheckOutDirectly() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment(), checkOut())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/check-out/" + RESERVATION_ID + "\"")))
                .andExpect(content().string(not(containsString("/reservations/" + RESERVATION_ID + "/check-out"))))
                .andExpect(content().string(not(containsString("Check out reservation"))));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/check-out/"))));
    }

    /**
     * Confirms a closed Folio stays viewable on every section but exposes no financial mutation controls.
     */
    @Test
    void shouldRenderCheckedOutFolioAsReadOnly() throws Exception {
        stubFolio("CHECKED_OUT", BigDecimal.ZERO, "PAID");
        var viewer = user("manager").authorities(managePayment());

        mockMvc.perform(get(FOLIO_PATH).with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This stay is checked out. Charges and payments are read-only.")))
                .andExpect(content().string(containsString("Total Payments")))
                .andExpect(content().string(not(containsString("Record Payment"))))
                .andExpect(content().string(not(containsString("Checkout Review"))))
                .andExpect(content().string(not(containsString("Quick Actions"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges").with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Total Charges")))
                .andExpect(content().string(not(containsString("Add Fixed Charge"))))
                .andExpect(content().string(not(containsString("charge-void-reason"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments").with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Total Payments")))
                .andExpect(content().string(not(containsString("Add as Pending"))))
                .andExpect(content().string(not(containsString("payment-refund-reason"))))
                .andExpect(content().string(not(containsString("payment-void-reason"))))
                .andExpect(content().string(not(containsString("Mark paid"))));
    }

    /**
     * Confirms the approved Overview composition: five KPI cards, the Charges, Payments and Outstanding Balance
     * column, and the Stay Summary and Recent Activity column. Quick Actions is removed. No unsupported mockup action appears.
     */
    @Test
    void shouldRenderApprovedOverviewCompositionWithOnlySupportedActions() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment(), checkOut())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("folio-kpi--nights"))))
                .andExpect(content().string(containsString("folio-kpi--guests")))
                .andExpect(content().string(containsString("Stay Summary")))
                .andExpect(content().string(not(containsString("Quick Actions"))))
                .andExpect(content().string(containsString("Recent Activity")))
                .andExpect(content().string(containsString("Room Type")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("Checkout Review")))
                .andExpect(content().string(not(containsString("Recent Charges"))))
                .andExpect(content().string(not(containsString("Recent Payments"))))
                .andExpect(content().string(not(containsString("Print Folio"))))
                .andExpect(content().string(not(containsString("Add Room Charge"))))
                .andExpect(content().string(not(containsString("Add Additional Revenue"))))
                .andExpect(content().string(not(containsString("Add Adjustment"))))
                .andExpect(content().string(not(containsString("Edit Dates"))))
                .andExpect(content().string(not(containsString("Room Rate"))));
    }

    /** Confirms the Outstanding Balance equation shows the authoritative StayBalance values in VND. */
    @Test
    void shouldRenderOutstandingEquationFromAuthoritativeBalance() throws Exception {
        stubFolio("CHECKED_IN", new BigDecimal("40.00"), "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("folio-outstanding--due")))
                .andExpect(content().string(containsString("100 VND")))
                .andExpect(content().string(containsString("60 VND")))
                .andExpect(content().string(containsString("40 VND")));
    }

    /** Confirms Nights counts the planned stay and shows how many nights a Stay Extension added. */
    @Test
    void shouldShowOriginalAndExtensionNightsSeparately() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                "CHECKED_IN",
                com.example.hotel.entity.booking.BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 14),
                BigDecimal.TEN,
                "VND",
                null,
                List.of()));
        when(stayExtensionService.summary(RESERVATION_ID)).thenReturn(new StayExtensionSummaryResponse(
                BigDecimal.TEN,
                new BigDecimal("2000"),
                new BigDecimal("2010"),
                List.of(new StayExtensionSummaryResponse.Event(
                        1, LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 14), List.of()))));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(Original 1 + 2 extended)")));
    }

    /** Confirms Recent Activity lists real audited actions newest first, using localized action labels. */
    @Test
    void shouldShowRecentActivityFromAuditTimelineNewestFirst() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");
        when(reservationActivityQueryService.findByReservationId(RESERVATION_ID)).thenReturn(List.of(
                new ReservationActivityEntry(Instant.parse("2026-09-11T10:00:00Z"), "front-desk", "CHECK_IN"),
                new ReservationActivityEntry(Instant.parse("2026-09-11T12:00:00Z"), "manager", "RECORD_PAYMENT")));

        String body = mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains("by manager"));
        assertTrue(body.indexOf("Payment recorded") < body.indexOf("Checked in"));
    }

    /** Confirms each Overview Charge row names the staff member who recorded it, from the resolved audit field. */
    @Test
    void shouldShowAddedByForOverviewCharges() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(new ChargeResponse(
                UUID.randomUUID(), STAY_ID, "BREAKFAST", null, null, null, new BigDecimal("80000"),
                Instant.parse("2026-09-11T10:00:00Z"), "ACTIVE", null, "front-desk")));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("front-desk")));
    }

    /**
     * Confirms the Overview Charges summary reads DATE, TYPE, QTY, UNIT PRICE, AMOUNT, ADDED BY, DESCRIPTION with
     * Description last and truncated, while the Charges tab table omits Description (it stays in Charge Detail). The
     * full text is kept as a title tooltip on the Overview.
     */
    @Test
    void shouldOrderChargeColumnsWithDescriptionLastBeforeActions() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(new ChargeResponse(
                UUID.randomUUID(), STAY_ID, "BREAKFAST", "Extra long breakfast description", null, null,
                new BigDecimal("80000"), Instant.parse("2026-09-11T10:00:00Z"), "ACTIVE", null, "front-desk")));

        String overview = mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(overview.indexOf("Qty</th>") < overview.indexOf("Unit price</th>"));
        assertTrue(overview.indexOf("Unit price</th>") < overview.indexOf("Amount</th>"));
        assertTrue(overview.indexOf("Amount</th>") < overview.indexOf("Added by</th>"));
        assertTrue(overview.indexOf("Added by</th>") < overview.indexOf("Description</th>"));
        assertFalse(overview.contains("Action</th>"));
        assertTrue(overview.contains("title=\"Extra long breakfast description\""));

        String charges = mockMvc.perform(get(FOLIO_PATH + "?tab=charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(charges.indexOf("Qty</span>") < charges.indexOf("Unit price</span>"));
        assertTrue(charges.indexOf("Unit price</span>") < charges.indexOf("Amount</span>"));
        assertTrue(charges.indexOf("Amount</span>") < charges.indexOf("Added by</span>"));
        assertFalse(charges.contains("Description</th>"));
        assertTrue(charges.indexOf("Charge Detail") < charges.indexOf("Extra long breakfast description"));
        assertTrue(charges.contains("data-sort-key=\"added\""));
        assertFalse(charges.contains("Actions</th>"));
    }

    /**
     * Confirms the Overview GUEST card shows the reservation's Guest Code (linked to Guest Detail only for MANAGE_GUEST)
     * and the Guest Name, with no adult or child count, which Stay Summary still shows.
     */
    @Test
    void shouldShowGuestCodeAndNameInOverviewGuestCard() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        String withGuest = mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(
                        managePayment(), new SimpleGrantedAuthority("PERM_MANAGE_GUEST"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(withGuest.contains("href=\"/guests/"));
        assertTrue(withGuest.contains("GUEST-001"));
        assertTrue(withGuest.contains("Nguyen Van A"));
        String guestCard = withGuest.substring(withGuest.indexOf("folio-kpi--guests"), withGuest.indexOf("folio-overview\""));
        assertTrue(!guestCard.contains("Adults") && !guestCard.contains("Children"));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GUEST-001")))
                .andExpect(content().string(not(containsString("href=\"/guests/"))));
    }

    /**
     * Confirms the Stay Summary Room No. links to the existing Room Detail route only for users with MANAGE_ROOM, and
     * still shows the room number without the link otherwise.
     */
    @Test
    void shouldLinkRoomNumberInStaySummaryToRoomDetail() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(
                        managePayment(), new SimpleGrantedAuthority("PERM_MANAGE_ROOM"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/rooms/")))
                .andExpect(content().string(containsString("101")));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("101")))
                .andExpect(content().string(not(containsString("href=\"/rooms/"))));
    }

    /**
     * Confirms the Charges tab selects the first Charge by default, shows only that Charge's detail block, opens Add
     * Charge as a dialog rather than a permanent form, and has no Actions column or row menu.
     */
    @Test
    void shouldRenderChargesLayoutWithDetailPanelAndDialogs() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        UUID firstId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(
                chargedAt("Oldest item", "2026-10-02T10:58:00Z", firstId),
                chargedAt("Newest item", "2026-10-03T10:55:00Z", secondId)));

        String body = mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, body.split("folio-charge-row--selected", -1).length - 1);
        assertTrue(body.contains("data-charge-row=\"" + firstId + "\""));
        assertTrue(body.contains("id=\"folio-charge-detail-heading\""));
        assertTrue(!startTag(body, "data-charge-detail=\"" + firstId + "\"").contains("hidden"));
        assertTrue(startTag(body, "data-charge-detail=\"" + secondId + "\"").contains("hidden"));
        assertTrue(body.contains("id=\"folio-add-charge-dialog\""));
        assertTrue(body.contains("data-dialog-open=\"folio-add-charge-dialog\""));
        assertTrue(body.contains("data-autoopen=\"false\""));
        assertTrue(body.contains("data-dialog-open=\"folio-void-dialog-" + firstId + "\""));
        assertTrue(!body.contains("id=\"folio-add-charge-heading\""));
        assertTrue(!body.contains("table-actions"));
        assertTrue(!body.contains("folio-row-menu"));
    }

    /**
     * Confirms each eligible Charge carries its own Void Charge dialog, and that a ROOM Charge offers no void dialog
     * or button at all.
     */
    @Test
    void shouldOfferVoidOnlyForEligibleCharges() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        UUID manualId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(
                chargedAt("Manual item", "2026-10-02T10:58:00Z", manualId)));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"folio-void-dialog-" + manualId + "\"")));

        UUID roomId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(new ChargeResponse(
                roomId, STAY_ID, "ROOM", "Room charge", null, null, new BigDecimal("100.00"),
                Instant.parse("2026-10-02T10:58:00Z"), "ACTIVE", null, "system")));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("folio-void-dialog-" + roomId))))
                .andExpect(content().string(containsString("data-charge-row=\"" + roomId + "\"")));
    }

    /**
     * Confirms the same four summary cards appear once on Overview, Charges and Payments, sit above the tab content,
     * and never include a Nights card.
     */
    @Test
    void shouldShowSharedSummaryCardsOnEveryFolioTab() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        for (String query : List.of("", "?tab=charges", "?tab=payments")) {
            String body = mockMvc.perform(get(FOLIO_PATH + query).with(user("manager").authorities(managePayment())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            int cards = body.indexOf("class=\"folio-kpis\"");
            assertTrue(cards >= 0, "summary cards missing for " + query);
            assertEquals(-1, body.indexOf("class=\"folio-kpis\"", cards + 1));
            assertTrue(cards < body.indexOf("class=\"detail-tabs folio-tabs\"") || cards > body.indexOf("folio-tabs"));
            assertTrue(body.contains("folio-kpi--guests"));
            assertTrue(body.contains("Total Charges"));
            assertTrue(body.contains("Total Payments"));
            assertTrue(body.contains("Outstanding Balance"));
            assertTrue(!body.contains("folio-kpi--nights"));
        }
    }

    /** Returns the opening tag that carries the given attribute value, so tests can check its hidden state. */
    private static String startTag(String body, String attribute) {
        int at = body.indexOf(attribute);
        return body.substring(body.lastIndexOf('<', at), body.indexOf('>', at) + 1);
    }

    /**
     * Confirms the Add Charge modal opens on Fixed Amount with only that form visible, the footer submits the visible
     * form, and a rejected Itemized submission reopens the modal on the Itemized tab.
     */
    @Test
    void shouldOpenAddChargeModalOnFixedAmountAndReopenOnSubmittedMode() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        String body = mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!startTag(body, "id=\"folio-charge-form-fixed\"").contains("hidden"));
        assertTrue(startTag(body, "id=\"folio-charge-form-itemized\"").contains("hidden"));
        assertTrue(body.contains("form=\"folio-charge-form-fixed\""));

        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        String rejected = mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "SERVICE")
                        .param("quantity", "2")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(!startTag(rejected, "id=\"folio-charge-form-itemized\"").contains("hidden"));
        assertTrue(rejected.contains("form=\"folio-charge-form-itemized\""));
    }

    /** Confirms a rejected Add Charge submission reopens the Add Charge dialog so the errors stay in context. */
    @Test
    void shouldReopenAddChargeDialogOnValidationError() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "")
                        .param("amount", "")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-autoopen=\"true\"")));
    }

    /**
     * Confirms Recent Activity renders exactly once on the Overview and Charges sections, inside the main Folio layout
     * and never after it, so no duplicated block escapes the application content area.
     */
    @Test
    void shouldRenderRecentActivityOnceInsideMainLayout() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        for (String query : List.of("", "?tab=charges")) {
            String body = mockMvc.perform(get(FOLIO_PATH + query).with(user("manager").authorities(managePayment())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            int heading = body.indexOf("id=\"folio-activity-heading\"");
            assertTrue(heading >= 0, "Recent Activity missing for " + query);
            assertEquals(-1, body.indexOf("id=\"folio-activity-heading\"", heading + 1));
            assertTrue(heading < body.indexOf("</main>"));
        }
    }

    /** Confirms the restored Overview section labels are localized in Vietnamese. */
    @Test
    void shouldLocalizeOverviewCompositionInVietnamese() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).cookie(language("vi")).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tóm tắt lưu trú")))
                .andExpect(content().string(containsString("Số dư còn lại")))
                .andExpect(content().string(not(containsString("Thao tác nhanh"))))
                .andExpect(content().string(containsString("Hoạt động gần đây")))
                .andExpect(content().string(not(containsString("Stay Summary"))));
    }

    /** Confirms CHECK_OUT alone cannot access detailed Folio financial information. */
    @Test
    void shouldRejectCheckOutOnlyUserFromDetailedFolio() throws Exception {
        mockMvc.perform(get(FOLIO_PATH).with(user("staff").authorities(checkOut())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a valid Charge form is CSRF-protected and uses PRG back to the Charges section on success. */
    @Test
    void shouldCreateChargeWithCsrfAndRedirectToCharges() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "SERVICE")
                        .param("amount", "100.00")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=charges"));
    }

    /** Confirms the itemized Charge form submits quantity and unit price without a client amount. */
    @Test
    void shouldCreateItemizedChargeWithCsrfAndRedirectToCharges() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "BREAKFAST")
                        .param("quantity", "2")
                        .param("unitPrice", "150000")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=charges"));

        ArgumentCaptor<ChargeCreateRequest> captor = ArgumentCaptor.forClass(ChargeCreateRequest.class);
        verify(chargeService).create(eq(STAY_ID), captor.capture());
        assertNull(captor.getValue().amount());
        assertEquals(new BigDecimal("2"), captor.getValue().quantity());
        assertEquals(new BigDecimal("150000"), captor.getValue().unitPrice());
    }

    /** Confirms the new Charge-void success flash message is localized in EN and VI. */
    @Test
    void shouldShowLocalizedChargeVoidSuccessFlashMessage() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        UUID chargeId = UUID.randomUUID();
        when(chargeService.voidCharge(eq(chargeId), any())).thenReturn(fixedCharge());

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges/{chargeId}/void", RESERVATION_ID, chargeId)
                        .param("reason", "Entered by mistake")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Charge voided successfully."));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges/{chargeId}/void", RESERVATION_ID, chargeId)
                        .param("reason", "Entered by mistake")
                        .cookie(language("vi"))
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Đã hủy khoản phí thành công."));
    }

    /** Confirms the new Payment-void success flash message is localized in EN and VI. */
    @Test
    void shouldShowLocalizedPaymentVoidSuccessFlashMessage() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        UUID paymentId = UUID.randomUUID();
        when(paymentService.voidPayment(eq(paymentId), any())).thenReturn(payment("VOIDED"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments/{paymentId}/void", RESERVATION_ID, paymentId)
                        .param("reason", "Duplicate entry")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Payment voided successfully."));

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments/{paymentId}/void", RESERVATION_ID, paymentId)
                        .param("reason", "Duplicate entry")
                        .cookie(language("vi"))
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Đã hủy thanh toán thành công."));
    }

    /**
     * Confirms every new Charge-void failure reason is localized in EN and VI, none of them the raw
     * English domain-exception text leaking through untranslated.
     */
    @Test
    void shouldShowLocalizedChargeVoidErrorMessages() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        UUID chargeId = UUID.randomUUID();
        record Case(String messageKey, String english, String vietnamese) {}
        List<Case> cases = List.of(
                new Case("payment.folio.charge.error.voidReasonRequired",
                        "A reason is required to void a charge.", "Vui lòng nhập lý do để hủy khoản phí."),
                new Case("payment.folio.charge.error.voidNotCheckedIn",
                        "Charges can be voided only for checked-in stays.",
                        "Chỉ có thể hủy khoản phí khi lưu trú đang ở trạng thái đã nhận phòng."),
                new Case("payment.folio.charge.error.voidRoomNotAllowed",
                        "Room charges cannot be voided.", "Không thể hủy phí phòng (ROOM)."),
                new Case("payment.folio.charge.error.voidNotActive",
                        "Only an active charge can be voided.", "Chỉ có thể hủy khoản phí đang hiệu lực."),
                new Case("payment.folio.charge.error.voidRevenueInconsistent",
                        "The linked additional revenue for this charge is missing or inconsistent. Nothing was voided.",
                        "Doanh thu bổ sung liên kết với khoản phí này bị thiếu hoặc không nhất quán. Không có gì được hủy."));

        for (Case testCase : cases) {
            when(chargeService.voidCharge(eq(chargeId), any())).thenThrow(new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT, testCase.messageKey(), "unused-english-reason"));

            mockMvc.perform(post("/reservations/{reservationId}/folio/charges/{chargeId}/void", RESERVATION_ID, chargeId)
                            .param("reason", "x")
                            .with(user("manager").authorities(managePayment()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("errorMessage", testCase.english()));

            mockMvc.perform(post("/reservations/{reservationId}/folio/charges/{chargeId}/void", RESERVATION_ID, chargeId)
                            .param("reason", "x")
                            .cookie(language("vi"))
                            .with(user("manager").authorities(managePayment()))
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("errorMessage", testCase.vietnamese()));
        }
    }

    /** Confirms both Charge statuses (ACTIVE and VOIDED) render through i18n, never the raw enum name. */
    @Test
    void shouldRenderLocalizedChargeStatusLabels() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        ChargeResponse voided = new ChargeResponse(
                UUID.randomUUID(), STAY_ID, "MINIBAR", "cola", null, null,
                new BigDecimal("100000"), Instant.parse("2026-09-11T10:00:00Z"), "VOIDED", "Duplicate entry");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge(), voided));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment("PENDING")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00")));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Active<")))
                .andExpect(content().string(containsString(">Voided<")))
                .andExpect(content().string(not(containsString(">ACTIVE<"))))
                .andExpect(content().string(not(containsString(">VOIDED<"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .cookie(language("vi"))
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Đang hiệu lực<")))
                .andExpect(content().string(containsString(">Đã hủy<")));
    }

    /** Confirms the record-paid action calls the direct PAID recording operation and redirects to Payments. */
    @Test
    void shouldRecordPaymentAsPaidWithCsrfAndRedirectToPayments() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        when(paymentService.recordPaid(eq(STAY_ID), any())).thenReturn(payment("PAID"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments/record-paid", RESERVATION_ID)
                        .param("amount", "100.00")
                        .param("currency", "VND")
                        .param("method", "CASH")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=payments"));

        verify(paymentService).recordPaid(eq(STAY_ID), any());
    }

    /** Confirms the existing Add-as-Pending Payment workflow still creates a PENDING Payment unchanged. */
    @Test
    void shouldStillCreatePendingPaymentThroughExistingWorkflow() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        when(paymentService.create(eq(STAY_ID), any())).thenReturn(payment("PENDING"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments", RESERVATION_ID)
                        .param("amount", "100.00")
                        .param("currency", "VND")
                        .param("method", "CASH")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=payments"));

        verify(paymentService).create(eq(STAY_ID), any());
    }

    /** Confirms a refund submission passes the staff-supplied reason to the Payment service. */
    @Test
    void shouldRefundWithReasonAndRedirectToPayments() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.refund(eq(paymentId), any())).thenReturn(payment("REFUNDED"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments/{paymentId}/refund",
                                RESERVATION_ID, paymentId)
                        .param("reason", "Guest cancelled stay")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=payments"));

        ArgumentCaptor<com.example.hotel.dto.booking.request.PaymentRefundRequest> captor =
                ArgumentCaptor.forClass(com.example.hotel.dto.booking.request.PaymentRefundRequest.class);
        verify(paymentService).refund(eq(paymentId), captor.capture());
        assertEquals("Guest cancelled stay", captor.getValue().reason());
    }

    /** Confirms a refund submission without a reason still reaches the service, which rejects it. */
    @Test
    void shouldSurfaceServiceRejectionWhenRefundReasonMissing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.refund(eq(paymentId), any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "reason is required"));
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(post("/reservations/{reservationId}/folio/payments/{paymentId}/refund",
                                RESERVATION_ID, paymentId)
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FOLIO_PATH + "?tab=payments"));
    }

    /** Confirms a refunded Payment's reason is displayed read-only in the Payment history. */
    @Test
    void shouldDisplayRefundReasonReadOnlyInPaymentHistory() throws Exception {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge()));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment("REFUNDED", "Guest cancelled stay")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00")));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reason: Guest cancelled stay")));
    }

    /** Confirms the Add Payment form marks the Reference field as required specifically for OTA. */
    @Test
    void shouldReflectOtaReferenceRequirementInAddPaymentForm() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Required for OTA payments.")))
                .andExpect(content().string(containsString("data-reference-hint")));
    }

    /** Confirms Folio financial POST routes reject browser submissions without a CSRF token. */
    @Test
    void shouldRequireCsrfForChargeCreation() throws Exception {
        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "SERVICE")
                        .param("amount", "100.00")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the Overview shows the reservation, stay and room context with real navigation targets. */
    @Test
    void shouldRenderReservationAndStayContextWithRealNavigation() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Folio — Reservation R20260911-000001")))
                .andExpect(content().string(containsString("Back to Reservation R20260911-000001")))
                .andExpect(content().string(containsString("href=\"/reservations/" + RESERVATION_ID + "\"")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("Room 101")))
                .andExpect(content().string(containsString("href=\"" + FOLIO_PATH + "?tab=charges\"")))
                .andExpect(content().string(containsString("href=\"" + FOLIO_PATH + "?tab=payments\"")))
                .andExpect(content().string(containsString("aria-current=\"page\"")));
    }

    /**
     * Confirms the Overview KPIs come straight from the authoritative balance: a VOIDED Charge that is
     * listed for history is not added into Active Charges, and Outstanding is the balance's own value.
     */
    @Test
    void shouldUseAuthoritativeBalanceForOverviewKpisAndExcludeVoidedCharges() throws Exception {
        ChargeResponse voided = new ChargeResponse(
                UUID.randomUUID(), STAY_ID, "MINIBAR", "cola", null, null,
                new BigDecimal("500"), Instant.parse("2026-09-11T10:00:00Z"), "VOIDED", "Duplicate entry");
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge(), voided));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment("PAID")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60")));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("folio-kpi--due")))
                .andExpect(content().string(containsString("100 VND")))
                .andExpect(content().string(containsString("40 VND")))
                .andExpect(content().string(containsString("60 VND")))
                .andExpect(content().string(not(containsString("600 VND"))))
                .andExpect(content().string(not(containsString("Nothing left to collect"))));
    }

    /** Confirms a settled Folio presents Outstanding as zero with the settled state rather than a due state. */
    @Test
    void shouldShowSettledOutstandingWhenNothingLeftToCollect() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("folio-kpi--settled")))
                .andExpect(content().string(containsString("folio-outstanding--settled")))
                .andExpect(content().string(not(containsString("folio-kpi--due"))))
                .andExpect(content().string(not(containsString("folio-outstanding--due"))));
    }

    /** Confirms the Overview lists only the five most recent Charges, newest first. */
    @Test
    void shouldSummarizeOnlyFiveMostRecentChargesOnOverview() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        List<ChargeResponse> charges = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            charges.add(new ChargeResponse(
                    UUID.randomUUID(), STAY_ID, "SERVICE", "Charge-" + index, null, null,
                    new BigDecimal(index * 1000), Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index * 60L),
                    "ACTIVE", null));
        }
        when(chargeService.findByStayId(STAY_ID)).thenReturn(charges);

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("6,000 VND")))
                .andExpect(content().string(containsString("2,000 VND")))
                .andExpect(content().string(not(containsString("1,000 VND"))));
    }

    /**
     * Confirms the Overview Charges are ordered by actual chargedAt ascending, oldest first and newest last, regardless
     * of the order the service supplies, with the Charge identifier breaking ties between identical timestamps.
     */
    @Test
    void shouldOrderOverviewChargesOldestFirstByChargedAt() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // 02/10/2026 17:58, 03/10/2026 16:48 and 03/10/2026 17:55 in Asia/Ho_Chi_Minh (UTC+7).
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(
                chargedAt("Newest", "2026-10-03T10:55:00Z", highId),
                chargedAt("Tied-high", "2026-10-02T10:58:00Z", highId),
                chargedAt("Middle", "2026-10-03T09:48:00Z", lowId),
                chargedAt("Tied-low", "2026-10-02T10:58:00Z", lowId),
                chargedAt("Oldest", "2026-10-01T08:00:00Z", highId)));

        String body = mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.indexOf("Oldest") < body.indexOf("Tied-low"));
        assertTrue(body.indexOf("Tied-low") < body.indexOf("Tied-high"));
        assertTrue(body.indexOf("Tied-high") < body.indexOf("Middle"));
        assertTrue(body.indexOf("Middle") < body.indexOf("Newest"));
    }

    /** Creates a fixed-amount Charge with a description, so its Overview row can be located by text. */
    private ChargeResponse chargedAt(String description, String chargedAt, UUID id) {
        return new ChargeResponse(
                id, STAY_ID, "SERVICE", description, null, null, new BigDecimal("1000"),
                Instant.parse(chargedAt), "ACTIVE", null, "front-desk");
    }

    /**
     * Confirms the Overview Charges summary ends with Description and has no row action column, and that the Charges
     * tab table omits the Description column (Description is shown in Charge Detail instead).
     */
    @Test
    void shouldShowDescriptionAsLastOverviewChargesColumnWithoutActionColumn() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(fixedCharge()));

        mockMvc.perform(get(FOLIO_PATH).with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("folio-description")))
                .andExpect(content().string(not(containsString("#charge-"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("folio-description"))));
    }

    /**
     * Confirms the Charges table exposes sortable headers for the approved columns, defaults to DATE ascending,
     * carries the underlying sort values on each row (never formatted text), and loads the sorting script.
     */
    @Test
    void shouldRenderSortableChargeHeadersWithUnderlyingSortValues() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        ChargeResponse breakfast = itemizedCharge();
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge(), breakfast));

        String body = mockMvc.perform(get(FOLIO_PATH).param("tab", "charges")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String key : List.of("date", "type", "qty", "price", "amount", "status", "added")) {
            assertTrue(body.contains("data-sort-key=\"" + key + "\""));
        }
        assertFalse(body.contains("data-sort-key=\"description\""));
        assertTrue(body.contains("aria-sort=\"ascending\""));
        assertTrue(body.contains("data-sort-amount=\"90000\""));
        assertTrue(body.contains("data-sort-qty=\"3\""));
        assertTrue(body.contains("data-sort-price=\"30000\""));
        assertFalse(body.contains("data-sort-added="));
        assertTrue(body.contains("/js/stay/folio-charges.js"));
        assertTrue(body.contains("data-charge-row=\"" + breakfast.id() + "\""));
    }

    /** Confirms no unsupported or out-of-scope mockup control renders on any Folio section. */
    @Test
    void shouldNotRenderUnsupportedMockupActionsOnAnySection() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        var viewer = user("manager").authorities(managePayment(), checkOut());

        for (String tab : List.of("overview", "charges", "payments")) {
            mockMvc.perform(get(FOLIO_PATH).param("tab", tab).with(viewer))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Add Room Charge"))))
                    .andExpect(content().string(not(containsString("Add Adjustment"))))
                    .andExpect(content().string(not(containsString("Add Additional Revenue"))))
                    .andExpect(content().string(not(containsString("Edit Charge"))))
                    .andExpect(content().string(not(containsString("Edit Payment"))))
                    .andExpect(content().string(not(containsString("Print Folio"))))
                    .andExpect(content().string(not(containsString("Edit Dates"))));
        }
    }

    /** Confirms each section renders the approved empty state when no Charges or Payments exist. */
    @Test
    void shouldRenderEmptyStatesWhenNoChargesOrPaymentsExist() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of());
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of());
        var viewer = user("manager").authorities(managePayment());

        mockMvc.perform(get(FOLIO_PATH).with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No charges recorded yet.")))
                .andExpect(content().string(containsString("No payments recorded yet.")))
                .andExpect(content().string(not(containsString("folio-table"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "charges").with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No charges recorded yet.")))
                .andExpect(content().string(not(containsString("folio-table"))));

        mockMvc.perform(get(FOLIO_PATH).param("tab", "payments").with(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No payments recorded yet.")))
                .andExpect(content().string(not(containsString("folio-table"))));
    }

    /** Confirms an unrecognized section falls back to the Overview rather than failing. */
    @Test
    void shouldFallBackToOverviewForUnknownSection() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get(FOLIO_PATH).param("tab", "unknown")
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Outstanding Balance")))
                .andExpect(content().string(not(containsString("Add Fixed Charge"))));
    }

    /** Confirms the Overview is available in Vietnamese with the approved VI financial labels. */
    @Test
    void shouldRenderVietnameseOverviewLabels() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get(FOLIO_PATH).cookie(language("vi"))
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tổng phí")))
                .andExpect(content().string(containsString("Tổng thanh toán")))
                .andExpect(content().string(containsString("Số dư còn lại")))
                .andExpect(content().string(containsString("Chi tiết")));
    }

    /** Stubs the complete service-backed model required to render a Folio. */
    private void stubFolio(String stayStatus, BigDecimal outstanding, String paymentStatus) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation(stayStatus));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay(stayStatus));
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge()));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment(paymentStatus)));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100.00"),
                new BigDecimal("100.00").subtract(outstanding),
                outstanding));
        when(guestQueryService.findForReservationCreation(any())).thenReturn(
                new GuestLookupResponse(UUID.randomUUID(), "GUEST-001", "Nguyen Van A", null, null, null));
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(
                new CurrentRoomResponse(UUID.randomUUID(), UUID.randomUUID(), "101",
                        Instant.parse("2026-09-11T10:00:00Z"))));
    }

    /** Gives the Overview's supporting lookups a safe default so every Folio test resolves them. */
    @BeforeEach
    void stubOverviewSupport() {
        when(roomQueryService.findAllByIds(any())).thenReturn(List.of(
                new RoomResponse(UUID.randomUUID(), "101", new RoomTypeResponse(UUID.randomUUID(), "DBL", "Double Room"),
                        "1", "OCCUPIED", true)));
        when(stayExtensionService.summary(RESERVATION_ID)).thenReturn(
                new StayExtensionSummaryResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
    }

    /** Creates the Reservation context shown by the Folio. */
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

    /** Creates the Stay context shown by the Folio. */
    private StayResponse stay(String status) {
        return new StayResponse(
                STAY_ID,
                status,
                Instant.parse("2026-09-11T10:00:00Z"),
                "CHECKED_OUT".equals(status) ? Instant.parse("2026-09-12T10:00:00Z") : null);
    }

    /** Creates a representative Charge entry. */
    private ChargeResponse charge() {
        return new ChargeResponse(
                UUID.randomUUID(),
                STAY_ID,
                "ROOM",
                "Room charge",
                new BigDecimal("1.500000"),
                new BigDecimal("66.666667"),
                new BigDecimal("100.00"),
                Instant.parse("2026-09-11T10:00:00Z"),
                "ACTIVE",
                null);
    }

    /** Creates a fixed-amount Charge with absent optional quantity and unit price. */
    private ChargeResponse fixedCharge() {
        return new ChargeResponse(
                UUID.randomUUID(),
                STAY_ID,
                "BREAKFAST",
                null,
                null,
                null,
                new BigDecimal("80000"),
                Instant.parse("2026-09-11T10:00:00Z"),
                "ACTIVE",
                null);
    }

    /** Creates an itemized Charge whose quantity should render without trailing zeroes. */
    private ChargeResponse itemizedCharge() {
        return new ChargeResponse(
                UUID.randomUUID(),
                STAY_ID,
                "LAUNDRY",
                null,
                new BigDecimal("3.000000"),
                new BigDecimal("30000"),
                new BigDecimal("90000"),
                Instant.parse("2026-09-11T10:00:00Z"),
                "ACTIVE",
                null);
    }

    /** Creates a representative Payment entry. */
    private PaymentResponse payment(String status) {
        return payment(status, null);
    }

    /** Creates a representative Payment entry with an optional refund reason. */
    private PaymentResponse payment(String status, String refundReason) {
        return new PaymentResponse(
                UUID.randomUUID(),
                STAY_ID,
                new BigDecimal("100.00"),
                "VND",
                null,
                new BigDecimal("100.00"),
                "CASH",
                status,
                "PAID".equals(status) ? Instant.parse("2026-09-11T11:00:00Z") : null,
                null,
                refundReason,
                null);
    }

    /** Creates a cross-currency Payment entry: 120 USD received, applied as 3,000,000 VND. */
    private PaymentResponse crossCurrencyPayment(String status) {
        return new PaymentResponse(
                UUID.randomUUID(),
                STAY_ID,
                new BigDecimal("120.00"),
                "USD",
                new BigDecimal("25000.000000"),
                new BigDecimal("3000000.000000"),
                "BANK_TRANSFER",
                status,
                "PAID".equals(status) ? Instant.parse("2026-09-11T11:00:00Z") : null,
                null,
                null,
                null);
    }

    /** Builds the approved Folio financial authority. */
    private SimpleGrantedAuthority managePayment() {
        return new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT");
    }

    /** Builds the check-out authority that must not grant detailed Folio access. */
    private SimpleGrantedAuthority checkOut() {
        return new SimpleGrantedAuthority("PERM_CHECK_OUT");
    }

    /** Enables method-security interception for this MVC test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {

        /**
         * Supplies a fixed hotel Clock three days after the fixture's planned check-out (12 September 2026).
         *
         * @return fixed Clock on 15 September 2026 in the hotel timezone
         */
        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-15T05:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
