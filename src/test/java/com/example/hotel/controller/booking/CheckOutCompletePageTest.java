package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.dto.booking.response.CheckOutCompleteResponse;
import com.example.hotel.dto.booking.response.CheckOutFinancialSummary;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckOutQueryService;
import com.example.hotel.service.booking.ReservationService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Checkout Complete screen: real data, multi-room wording, financial/route permissions, read-only folio and EN/VI. */
@WebMvcTest(value = CheckOutPageController.class, properties = "hotel.i18n.default-locale=en")
@Import({CheckOutCompletePageTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class CheckOutCompletePageTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_101 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_102 = UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    private CheckOutCompleteResponse complete(CheckOutCompleteResponse.CheckedOutRoom... rooms) {
        return new CheckOutCompleteResponse(ID, "R20261002-000001", "CHECKED_OUT", "CHECKED_OUT", UUID.randomUUID(), "G000004",
                List.of(rooms), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 7), Instant.parse("2026-10-02T07:30:00Z"),
                Instant.parse("2026-10-07T04:25:00Z"), "admin", 2, 1);
    }

    private static CheckOutCompleteResponse.CheckedOutRoom room101() {
        return new CheckOutCompleteResponse.CheckedOutRoom(ROOM_101, "101", "Double Room", "DIRTY");
    }

    private static CheckOutCompleteResponse.CheckedOutRoom room102() {
        return new CheckOutCompleteResponse.CheckedOutRoom(ROOM_102, "102", "Twin Room", "DIRTY");
    }

    private CheckOutFinancialSummary finance() {
        ChargeResponse room = new ChargeResponse(UUID.randomUUID(), UUID.randomUUID(), "ROOM", "Double Room (101)",
                new BigDecimal("5"), new BigDecimal("1200000"), new BigDecimal("6000000"), Instant.parse("2026-10-02T07:30:00Z"),
                "ACTIVE", null);
        PaymentResponse cash = new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("6000000"), "VND",
                BigDecimal.ONE, new BigDecimal("6000000"), "BANK_TRANSFER", "PAID", Instant.parse("2026-10-02T09:00:00Z"),
                "TRX123456", null, null, "admin");
        return new CheckOutFinancialSummary(new BigDecimal("6000000"), new BigDecimal("6000000"), BigDecimal.ZERO, "VND",
                List.of(room), List.of(cash), Map.of(ROOM_101, new BigDecimal("1200000")));
    }

    /** Confirms the screen shows the actual reservation, stay, room, financial, status and actor data. */
    @Test
    void shouldRenderTheActualCheckedOutReservationStayRoomAndFinancialData() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101()));
        when(queryService.financialSummary(ID)).thenReturn(finance());

        mockMvc.perform(get("/check-out/{id}/complete", ID)
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_VIEW_BOOKING", "PERM_MANAGE_HOUSEKEEPING", "PERM_MANAGE_ROOM")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Checkout Complete - Reservation #R20261002-000001")))
                .andExpect(content().string(containsString("status-badge--checked_out")))
                .andExpect(content().string(containsString("Checkout completed successfully")))
                .andExpect(content().string(containsString("The reservation has been checked out and the room has been marked as DIRTY for housekeeping.")))
                .andExpect(content().string(containsString("by admin")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("5 nights")))
                .andExpect(content().string(containsString("1,200,000 VND")))
                .andExpect(content().string(containsString("id=\"total-charges\"")))
                .andExpect(content().string(containsString("TRX123456")))
                .andExpect(content().string(containsString("Room 101 is now")))
                .andExpect(content().string(containsString("Dirty")))
                .andExpect(content().string(containsString("Outstanding balance: 0 VND")));
        String body = mockMvc.perform(get("/check-out/{id}/complete", ID)
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                body.indexOf("id=\"outstanding-amount\"") > 0 && body.substring(body.indexOf("id=\"outstanding-amount\"")).contains("0 VND"));
    }

    /** Confirms the folio stays read-only: the screen offers no form, charge/payment mutation or confirm action. */
    @Test
    void shouldOfferNoFinancialMutationOrCheckoutActionOnTheCompletedFolio() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101()));
        when(queryService.financialSummary(ID)).thenReturn(finance());

        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/confirm\""))))
                .andExpect(content().string(not(containsString("/folio/charges"))))
                .andExpect(content().string(not(containsString("/folio/payments\""))))
                .andExpect(content().string(not(containsString("confirm-checkout"))))
                .andExpect(content().string(not(containsString("Add Charge"))))
                .andExpect(content().string(not(containsString("Add Payment"))))
                .andExpect(content().string(containsString("href=\"/reservations/" + ID + "/folio?tab=charges\"")))
                .andExpect(content().string(containsString("href=\"/reservations/" + ID + "/folio?tab=payments\"")));
    }

    /** Confirms a multi-room Stay lists every room and the wording never implies a single room, with no single View Room action. */
    @Test
    void shouldRepresentEveryRoomOfAMultiRoomStay() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101(), room102()));
        when(queryService.financialSummary(ID)).thenReturn(finance());

        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_MANAGE_ROOM")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("all 2 rooms have been marked as DIRTY")))
                .andExpect(content().string(containsString("101, 102")))
                .andExpect(content().string(containsString("Double Room, Twin Room")))
                .andExpect(content().string(containsString("Room 101 is now")))
                .andExpect(content().string(containsString("Room 102 is now")))
                .andExpect(content().string(containsString("View Room 101")))
                .andExpect(content().string(containsString("View Room 102")))
                .andExpect(content().string(containsString("101 · ")));
    }

    /** Confirms CHECK_OUT alone sees no amount, table, folio link or privileged next action. */
    @Test
    void shouldHideFinancialsAndUnauthorizedActionsWithoutTheirPermissions() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101()));

        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"total-charges\""))))
                .andExpect(content().string(not(containsString("charges-summary"))))
                .andExpect(content().string(not(containsString("/folio"))))
                .andExpect(content().string(not(containsString("1,200,000"))))
                .andExpect(content().string(not(containsString("id=\"go-to-housekeeping\""))))
                .andExpect(content().string(not(containsString("id=\"view-reservation\""))))
                .andExpect(content().string(not(containsString("id=\"view-guest\""))))
                .andExpect(content().string(not(containsString("View Room 101"))))
                .andExpect(content().string(not(containsString("id=\"next-actions\""))))
                .andExpect(content().string(containsString("Back to Front Desk")));
        verify(queryService, never()).financialSummary(ID);
    }

    /** Confirms each Next Action appears only with its own permission. */
    @Test
    void shouldShowEachNextActionOnlyWithItsOwnPermission() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101()));

        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_HOUSEKEEPING")))
                .andExpect(content().string(containsString("id=\"go-to-housekeeping\"")))
                .andExpect(content().string(containsString("href=\"/housekeeping\"")))
                .andExpect(content().string(not(containsString("id=\"view-reservation\""))))
                .andExpect(content().string(not(containsString("View Room 101"))));
        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT", "PERM_VIEW_BOOKING", "PERM_MANAGE_GUEST")))
                .andExpect(content().string(containsString("id=\"view-reservation\"")))
                .andExpect(content().string(containsString("id=\"back-to-reservations\"")))
                .andExpect(content().string(containsString("id=\"view-guest\"")))
                .andExpect(content().string(not(containsString("id=\"go-to-housekeeping\""))));
        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT", "PERM_MANAGE_ROOM")))
                .andExpect(content().string(containsString("href=\"/rooms/" + ROOM_101 + "\"")));
    }

    /** Confirms a Reservation that is not CHECKED_OUT is sent back to the Review instead of rendering a false success. */
    @Test
    void shouldRedirectToReviewWhenTheReservationIsNotCheckedOut() throws Exception {
        when(queryService.complete(ID)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is not checked out"));

        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_CHECK_OUT")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-out/" + ID));
    }

    /** Confirms CHECK_OUT is required. */
    @Test
    void shouldForbidTheScreenWithoutCheckOutPermission() throws Exception {
        mockMvc.perform(get("/check-out/{id}/complete", ID).with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_PAYMENT")))
                .andExpect(status().isForbidden());
        verify(queryService, never()).complete(ID);
    }

    /** Confirms Vietnamese rendering of the heading, banner, panels and actions. */
    @Test
    void shouldRenderVietnamese() throws Exception {
        when(queryService.complete(ID)).thenReturn(complete(room101()));
        when(queryService.financialSummary(ID)).thenReturn(finance());

        mockMvc.perform(get("/check-out/{id}/complete", ID).cookie(new Cookie("pms-lang", "vi"))
                        .with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT", "PERM_MANAGE_HOUSEKEEPING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hoàn tất trả phòng - Đặt phòng #R20261002-000001")))
                .andExpect(content().string(containsString("Trả phòng thành công")))
                .andExpect(content().string(containsString("Trạng thái sau trả phòng")))
                .andExpect(content().string(containsString("Đến Buồng phòng")))
                .andExpect(content().string(containsString("Tóm tắt tài chính")))
                .andExpect(content().string(containsString("5 đêm")));
    }

    private static RequestPostProcessor perm(String... authorities) {
        return user("tester").authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
