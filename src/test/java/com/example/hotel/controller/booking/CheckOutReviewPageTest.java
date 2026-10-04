package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CheckOutFinancialSummary;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.ReservationService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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

/** Verifies the Checkout Review screen: READY / NOT READY, financial permission, current rooms, overdue, EN/VI and the confirm guard. */
@WebMvcTest(value = CheckOutPageController.class, properties = "hotel.i18n.default-locale=en")
@Import({CheckOutReviewPageTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class CheckOutReviewPageTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckOutQueryService queryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private com.example.hotel.service.customer.GuestQueryService guestQueryService;

    @MockitoBean
    private JwtService jwtService;

    private CheckOutReviewResponse review(boolean eligible, String readiness, long overdueDays, String status, List<CurrentRoomResponse> rooms) {
        return new CheckOutReviewResponse(ID, "R20261002-000001", status, eligible, UUID.randomUUID(), "G000004", rooms,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 7), Instant.parse("2026-10-02T07:30:00Z"), readiness,
                overdueDays, LocalDate.of(2026, 10, 7).plusDays(overdueDays), BookingSource.AGODA, "123456789", 2, 1,
                Instant.parse("2026-09-20T03:00:00Z"), "Double Room");
    }

    private static List<CurrentRoomResponse> room(String number) {
        return List.of(new CurrentRoomResponse(UUID.randomUUID(), UUID.randomUUID(), number, Instant.parse("2026-10-02T07:30:00Z")));
    }

    private CheckOutFinancialSummary finance(String outstanding) {
        ChargeResponse room = new ChargeResponse(UUID.randomUUID(), UUID.randomUUID(), "ROOM", "Double Room (101)",
                new BigDecimal("3"), new BigDecimal("1200000"), new BigDecimal("3600000"), Instant.parse("2026-10-02T07:30:00Z"),
                "ACTIVE", null);
        ChargeResponse voided = new ChargeResponse(UUID.randomUUID(), UUID.randomUUID(), "SERVICE", "Airport Transfer",
                BigDecimal.ONE, new BigDecimal("300000"), new BigDecimal("300000"), Instant.parse("2026-10-03T07:30:00Z"),
                "VOIDED", "Mistake");
        PaymentResponse cash = new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("2000000"), "VND",
                BigDecimal.ONE, new BigDecimal("2000000"), "CASH", "PAID", Instant.parse("2026-10-02T09:00:00Z"), null, null, null, "admin");
        PaymentResponse usd = new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("80"), "USD",
                new BigDecimal("25000"), new BigDecimal("2000000"), "BANK_TRANSFER", "PAID", Instant.parse("2026-10-05T09:00:00Z"),
                "TRX123456", null, null, "admin");
        BigDecimal due = new BigDecimal(outstanding);
        return new CheckOutFinancialSummary(new BigDecimal("3600000"), new BigDecimal("3600000").subtract(due), due, "VND",
                List.of(room, voided), List.of(cash, usd));
    }

    /** Confirms an eligible READY review with payment access shows a zero balance, the tables and the real confirm form. */
    @Test
    void shouldRenderReadyCheckoutWithConfirmAction() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("0"));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Checkout Review - Reservation R20261002-000001")))
                .andExpect(content().string(containsString("data-readiness=\"READY\"")))
                .andExpect(content().string(containsString("Ready for checkout")))
                .andExpect(content().string(containsString("<span>0 VND</span>")))
                .andExpect(content().string(containsString("breadcrumb-current\">Review</span>")))
                .andExpect(content().string(containsString("id=\"confirm-checkout\"")))
                .andExpect(content().string(containsString("action=\"/check-out/" + ID + "/confirm\"")))
                .andExpect(content().string(containsString("mark the room(s) as DIRTY")))
                .andExpect(content().string(not(containsString("id=\"go-to-payments\""))))
                .andReturn().getResponse().getContentAsString();
        // Charges: the VOIDED row is shown as voided, and the total is the authoritative one, not the sum of the rows.
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Double Room (101)") && body.contains("Voided"));
        // The voided status is the compact note inside the Amount cell, no longer a badge beside the description.
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("cor-note cor-note--void"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("status-badge status-badge--voided"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("3,600,000 VND"));
        // Payments: a USD tender shows its applied VND amount and a tender note.
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("TRX123456") && body.contains("Paid as USD"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("cor-note cor-note--tender"));
    }

    /** Confirms Overview/Charges/Payments carry Ctrl+1/2/3 (not Checkout), the shared date classes and the shortcut script, in EN and VI. */
    @Test
    void shouldExposeTabShortcutsAndDateColors() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("0"));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-shortcut=\"1\"")))
                .andExpect(content().string(containsString("aria-keyshortcuts=\"Control+2\"")))
                .andExpect(content().string(containsString("Shortcut: Ctrl+3")))
                .andExpect(content().string(containsString("/js/common/tab-shortcuts.js")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("data-shortcut=\"4\""));
        org.junit.jupiter.api.Assertions.assertEquals(2, body.split("date-value--check-in", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertEquals(1, body.split("date-value--check-out", -1).length - 1);
        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT"))
                        .cookie(new jakarta.servlet.http.Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Phím tắt: Ctrl+1")));
    }

    /** Confirms an outstanding balance is NOT READY: amount, the Go to Payments path, a disabled confirm and no confirm form. */
    @Test
    void shouldRenderNotReadyWithOutstandingAndPaymentPathAndNoConfirm() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "PAYMENT_REQUIRED", 0, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("2400000"));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-readiness=\"NOT_READY\"")))
                .andExpect(content().string(containsString("Not ready for checkout")))
                .andExpect(content().string(containsString("2,400,000 VND")))
                .andExpect(content().string(containsString("Cannot proceed to checkout")))
                .andExpect(content().string(containsString("id=\"go-to-payments\"")))
                .andExpect(content().string(containsString("href=\"/reservations/" + ID + "/folio?tab=payments\"")))
                .andExpect(content().string(containsString("id=\"confirm-checkout-disabled\"")))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout\""))))
                .andExpect(content().string(not(containsString("/confirm\""))));
    }

    /** Confirms CHECK_OUT alone sees only the READY / NOT READY state: no amounts, tables, folio links or financial read. */
    @Test
    void shouldHideEveryFinancialDetailWithoutManagePayment() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "PAYMENT_REQUIRED", 0, "CHECKED_IN", room("101")));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not ready for checkout")))
                .andExpect(content().string(containsString("must be settled by staff with payment access")))
                .andExpect(content().string(not(containsString("id=\"outstanding-amount\""))))
                .andExpect(content().string(not(containsString("id=\"charges-summary\""))))
                .andExpect(content().string(not(containsString("id=\"payments-summary\""))))
                .andExpect(content().string(not(containsString("id=\"go-to-payments\""))))
                .andExpect(content().string(not(containsString("/folio"))))
                .andExpect(content().string(not(containsString(" VND"))));
        verify(queryService, never()).financialSummary(any());
    }

    /** Confirms the Stay Information shows the CURRENT room after a Room Change and the Guest Code only. */
    @Test
    void shouldShowCurrentRoomAndOnlyTheGuestCode() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("305")));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString(">305<")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("G000004")))
                .andExpect(content().string(containsString("Agoda")))
                .andExpect(content().string(containsString("123456789")))
                .andExpect(content().string(containsString("2 adults, 1 child")))
                .andExpect(content().string(not(containsString("href=\"/guests/"))));
        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_GUEST")))
                .andExpect(content().string(containsString("href=\"/guests/")));
    }

    /** Confirms an overdue stay with a zero balance is still NOT READY with no confirm action, and no payment path is offered. */
    @Test
    void shouldKeepOverdueStayNotReadyEvenWhenPaid() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "READY", 2, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("0"));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_EXTEND_STAY")))
                .andExpect(content().string(containsString("id=\"overdue-departure\"")))
                .andExpect(content().string(containsString("id=\"overdue-extend-stay\"")))
                .andExpect(content().string(containsString("data-readiness=\"NOT_READY\"")))
                .andExpect(content().string(containsString("Overdue by 2 days. Extend the stay first.")))
                .andExpect(content().string(containsString("id=\"confirm-checkout-disabled\"")))
                .andExpect(content().string(not(containsString("id=\"go-to-payments\""))))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout\""))));
    }

    /** Confirms a reservation that is not CHECKED_IN gets the blocked notice and no confirm action. */
    @Test
    void shouldBlockAReservationThatIsNotCheckedIn() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "READY", 0, "CHECKED_OUT", List.of()));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("This reservation is not available for check-out.")))
                .andExpect(content().string(containsString("Current status: CHECKED_OUT.")))
                .andExpect(content().string(not(containsString("id=\"confirm-checkout\""))));
    }

    /** Confirms the Vietnamese rendering translates the screen and leaves no English UI strings behind. */
    @Test
    void shouldRenderInVietnamese() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "PAYMENT_REQUIRED", 0, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("2400000"));

        mockMvc.perform(get("/check-out/{id}", ID).cookie(new Cookie("pms-lang", "vi"))
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Xem lại trả phòng - Đặt phòng R20261002-000001")))
                .andExpect(content().string(containsString("Chưa sẵn sàng trả phòng")))
                .andExpect(content().string(containsString("Không thể tiến hành trả phòng")))
                .andExpect(content().string(containsString("Đến Thanh toán")))
                .andExpect(content().string(containsString("Danh sách kiểm tra trả phòng")))
                .andExpect(content().string(containsString("Xác nhận trả phòng")))
                .andExpect(content().string(not(containsString("Cannot proceed to checkout"))))
                .andExpect(content().string(not(containsString("Checkout Checklist"))))
                .andExpect(content().string(not(containsString("Confirm Checkout"))));
    }

    /** Confirms the READY confirmation dialog text is localized too. */
    @Test
    void shouldLocalizeTheConfirmationDialog() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("101")));

        mockMvc.perform(get("/check-out/{id}", ID).cookie(new Cookie("pms-lang", "vi")).with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("chuyển phòng sang DIRTY")));
    }

    /** Confirms Stay Information shows OTA Ref and the Room Rate of the CURRENT room, the latter only with payment access. */
    @Test
    void shouldShowRoomRateOfTheCurrentRoomOnlyWithPaymentAccess() throws Exception {
        List<CurrentRoomResponse> rooms = room("305");
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", rooms));
        CheckOutFinancialSummary base = finance("0");
        when(queryService.financialSummary(ID)).thenReturn(new CheckOutFinancialSummary(base.totalCharges(),
                base.totalPaidPayments(), base.outstanding(), "VND", base.charges(), base.payments(),
                java.util.Map.of(rooms.get(0).roomId(), new BigDecimal("1000000"))));

        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(content().string(containsString("OTA Ref")))
                .andExpect(content().string(containsString("Room Rate")))
                .andExpect(content().string(containsString("1,000,000 VND")))
                .andExpect(content().string(containsString("/ night")));
        mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("OTA Ref")))
                .andExpect(content().string(not(containsString("Room Rate"))));
    }

    /** Confirms Charges and Payments share the same four-column geometry and the checklist uses filled indicators. */
    @Test
    void shouldShareColumnGeometryAndUseFilledChecklistIndicators() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "PAYMENT_REQUIRED", 0, "CHECKED_IN", room("101")));
        when(queryService.financialSummary(ID)).thenReturn(finance("2400000"));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andReturn().getResponse().getContentAsString();
        String colgroup = "<colgroup>\n                                <col class=\"cor-col cor-col--1\" />\n                                <col class=\"cor-col cor-col--2\" />\n                                <col class=\"cor-col cor-col--3\" />\n                                <col class=\"cor-col cor-col--4\" />";
        org.junit.jupiter.api.Assertions.assertEquals(2, body.split(java.util.regex.Pattern.quote(colgroup), -1).length - 1);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("cor-check--ok"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("cor-check--fail"));
    }

    /** Confirms READY renders three filled-success checklist items and no failure item. */
    @Test
    void shouldRenderAllChecklistItemsAsSuccessWhenReady() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("101")));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT"))).andReturn().getResponse().getContentAsString();
        String list = body.substring(body.indexOf("cor-checklist"), body.indexOf("</ul>", body.indexOf("cor-checklist")));
        org.junit.jupiter.api.Assertions.assertEquals(3, list.split("cor-check--ok", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertFalse(list.contains("cor-check--fail"));
        org.junit.jupiter.api.Assertions.assertFalse(list.contains("M12 6.5v7"), "no exclamation mark when everything is satisfied");
    }

    /** Confirms NOT READY renders the failing item with the exclamation mark and the others as success. */
    @Test
    void shouldRenderFailingChecklistItemWithExclamationWhenNotReady() throws Exception {
        when(queryService.review(ID)).thenReturn(review(false, "PAYMENT_REQUIRED", 0, "CHECKED_IN", room("101")));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT"))).andReturn().getResponse().getContentAsString();
        String list = body.substring(body.indexOf("cor-checklist"), body.indexOf("</ul>", body.indexOf("cor-checklist")));
        org.junit.jupiter.api.Assertions.assertEquals(2, list.split("cor-check--ok", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertEquals(1, list.split("cor-check--fail", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertEquals(1, list.split("M12 6.5v7", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertTrue(list.indexOf("cor-check--fail") < list.indexOf("Outstanding balance is zero"));
    }

    /** Confirms Source and OTA Ref sit in the right-hand Stay group after Guests, and Guest Information has two columns. */
    @Test
    void shouldBalanceStayAndGuestColumns() throws Exception {
        when(queryService.review(ID)).thenReturn(review(true, "READY", 0, "CHECKED_IN", room("101")));
        when(guestQueryService.findForReservationCreation(any())).thenReturn(new com.example.hotel.dto.customer.response.GuestLookupResponse(
                ID, "G000004", "Chu Khoa", "a@example.com", "0901", "Vietnam", LocalDate.of(2000, 10, 4), "P1"));

        String body = mockMvc.perform(get("/check-out/{id}", ID).with(perm("PERM_CHECK_OUT"))).andReturn().getResponse().getContentAsString();
        String stay = body.substring(body.indexOf("cor-stay-heading"));
        int right = stay.indexOf("cor-facts--rooms");
        org.junit.jupiter.api.Assertions.assertTrue(stay.indexOf(">Source<") > right && stay.indexOf(">OTA Ref<") > stay.indexOf(">Source<"));
        org.junit.jupiter.api.Assertions.assertTrue(stay.indexOf(">Source<") > stay.indexOf(">Guests<"));
        org.junit.jupiter.api.Assertions.assertTrue(stay.indexOf(">Nights<") < right);
        String guest = body.substring(body.indexOf("cor-guest-heading"), body.indexOf("cor-stay-heading"));
        int side = guest.indexOf("cor-facts--side");
        org.junit.jupiter.api.Assertions.assertTrue(guest.indexOf(">Phone<") < side && guest.indexOf(">Email<") > side);
        org.junit.jupiter.api.Assertions.assertTrue(guest.indexOf(">ID / Passport Number<") > side);
    }

    private static RequestPostProcessor perm(String... authorities) {
        return user("tester").authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
