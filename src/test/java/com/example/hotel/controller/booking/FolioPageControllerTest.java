package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.StayBalance;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** Verifies Folio MVC authorization, mutable rendering, and post-redirect-get behavior. */
@WebMvcTest(FolioPageController.class)
@Import(FolioPageControllerTest.MethodSecurityTestConfiguration.class)
class FolioPageControllerTest {

    private static final UUID RESERVATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STAY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private JwtService jwtService;

    /** Confirms a payment manager can view a checked-in Folio with all approved mutable controls. */
    @Test
    void shouldRenderMutableFolioForManagePayment() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(view().name("stay/folio"))
                .andExpect(content().string(containsString("Total Charges")))
                .andExpect(content().string(containsString("Add Charge")))
                .andExpect(content().string(containsString("Add Payment")))
                .andExpect(content().string(containsString("Mark paid")))
                .andExpect(content().string(containsString("ROOM")))
                .andExpect(content().string(containsString("1.5")))
                .andExpect(content().string(not(containsString("1.500000"))))
                .andExpect(content().string(containsString("0 VND")));
    }

    /** Confirms the Folio currency is displayed and exposed to client-side JS via a data attribute. */
    @Test
    void shouldExposeFolioCurrencyToPageAndJs() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Folio Currency: <strong>VND</strong>")))
                .andExpect(content().string(containsString("data-reservation-currency=\"VND\"")));
    }

    /** Confirms Paid At renders as a compact local date-time instead of the raw ISO Instant. */
    @Test
    void shouldFormatPaidAtAsCompactLocalDateTimeInsteadOfRawInstant() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("1 USD = 25,000"))));
    }

    /** Confirms the Refund confirmation uses the original tender amount and currency, not appliedAmount. */
    @Test
    void shouldConfirmRefundUsingOriginalTenderAmount() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(crossCurrencyPayment("PAID")));

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-confirm-message=\"Refund 120 USD?\"")))
                .andExpect(content().string(not(containsString("Refund 3,000,000 VND?"))));
    }

    /** Confirms the Add Payment form offers both VND and USD regardless of Reservation currency. */
    @Test
    void shouldOfferBothCurrenciesInAddPaymentForm() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PENDING");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"VND\">VND</option>")))
                .andExpect(content().string(containsString("<option value=\"USD\">USD</option>")));
    }

    /** Confirms Payment lifecycle actions render only for the statuses that still permit a transition. */
    @Test
    void shouldRenderPaymentActionsOnlyForEligibleStatuses() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "FAILED");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Mark paid"))))
                .andExpect(content().string(not(containsString("Mark failed"))))
                .andExpect(content().string(not(containsString("Refund"))));
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
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

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3")))
                .andExpect(content().string(not(containsString("3.000000"))))
                .andExpect(content().string(containsString("30,000 VND")))
                .andExpect(content().string(containsString("90,000 VND")));
    }

    /** Confirms Folio payment and check-out actions expose their approved confirmation metadata. */
    @Test
    void shouldRenderDangerConfirmationMetadataForRefundAndCheckOut() throws Exception {
        stubFolio("CHECKED_IN", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment(), checkOut())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-confirm-title=\"Refund payment\"")))
                .andExpect(content().string(containsString("data-confirm-message=\"Refund 100 VND?\"")))
                .andExpect(content().string(containsString("data-confirm-label=\"Refund payment\"")))
                .andExpect(content().string(containsString("data-confirm-severity=\"DANGER\"")))
                .andExpect(content().string(containsString("data-confirm-title=\"Check out reservation\"")))
                .andExpect(content().string(containsString("Check out reservation R20260911-000001?")));
    }

    /** Confirms a closed Folio renders its history but no financial mutation controls. */
    @Test
    void shouldRenderCheckedOutFolioAsReadOnly() throws Exception {
        stubFolio("CHECKED_OUT", BigDecimal.ZERO, "PAID");

        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("manager").authorities(managePayment())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This Folio is closed and read-only.")))
                .andExpect(content().string(containsString("Total Paid")))
                .andExpect(content().string(not(containsString("Add Charge"))))
                .andExpect(content().string(not(containsString("Add Payment"))))
                .andExpect(content().string(not(containsString("Refund"))));
    }

    /** Confirms CHECK_OUT alone cannot access detailed Folio financial information. */
    @Test
    void shouldRejectCheckOutOnlyUserFromDetailedFolio() throws Exception {
        mockMvc.perform(get("/reservations/{reservationId}/folio", RESERVATION_ID)
                        .with(user("staff").authorities(checkOut())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a valid Charge form is CSRF-protected and uses PRG on success. */
    @Test
    void shouldCreateChargeWithCsrfAndRedirectToFolio() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "SERVICE")
                        .param("amount", "100.00")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID + "/folio"));
    }

    /** Confirms the itemized Charge form submits quantity and unit price without a client amount. */
    @Test
    void shouldCreateItemizedChargeWithCsrfAndRedirectToFolio() throws Exception {
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay("CHECKED_IN"));

        mockMvc.perform(post("/reservations/{reservationId}/folio/charges", RESERVATION_ID)
                        .param("type", "BREAKFAST")
                        .param("quantity", "2")
                        .param("unitPrice", "150000")
                        .with(user("manager").authorities(managePayment()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/" + RESERVATION_ID + "/folio"));

        ArgumentCaptor<ChargeCreateRequest> captor = ArgumentCaptor.forClass(ChargeCreateRequest.class);
        verify(chargeService).create(eq(STAY_ID), captor.capture());
        assertNull(captor.getValue().amount());
        assertEquals(new BigDecimal("2"), captor.getValue().quantity());
        assertEquals(new BigDecimal("150000"), captor.getValue().unitPrice());
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

    /** Supplies the complete service-backed model required to render a Folio. */
    private void stubFolio(String stayStatus, BigDecimal outstanding, String paymentStatus) {
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(reservation(stayStatus));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay(stayStatus));
        when(chargeService.findByStayId(STAY_ID)).thenReturn(List.of(charge()));
        when(paymentService.findByStayId(STAY_ID)).thenReturn(List.of(payment(paymentStatus)));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(new StayBalance(
                new BigDecimal("100.00"),
                new BigDecimal("100.00").subtract(outstanding),
                outstanding));
    }

    /** Creates the Reservation context shown by the Folio. */
    private ReservationDetailResponse reservation(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260911-000001",
                UUID.randomUUID(),
                "GUEST-001",
                status,
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
                Instant.parse("2026-09-11T10:00:00Z"));
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
                Instant.parse("2026-09-11T10:00:00Z"));
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
                Instant.parse("2026-09-11T10:00:00Z"));
    }

    /** Creates a representative Payment entry. */
    private PaymentResponse payment(String status) {
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
    static class MethodSecurityTestConfiguration {}
}
