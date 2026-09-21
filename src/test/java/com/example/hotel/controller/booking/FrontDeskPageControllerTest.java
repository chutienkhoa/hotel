package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskRoomResponse;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.FrontDeskQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Verifies the Front Desk page: permission-aware views, no cross-tab data, money visibility, actions, EN/VI. */
@WebMvcTest(FrontDeskPageController.class)
@Import({FrontDeskPageControllerTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class FrontDeskPageControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private static final UUID RES = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FrontDeskQueryService queryService;

    @MockitoBean
    private JwtService jwtService;

    /** Stubs an arrival with a dirty room, an overdue arrival, a payment-required overdue departure and an in-house stay. */
    @BeforeEach
    void stubData() {
        when(queryService.hotelToday()).thenReturn(TODAY);
        FrontDeskRoomResponse dirty = new FrontDeskRoomResponse(ROOM, "203", "Double", ArrivalIssueCode.ROOM_DIRTY);
        ArrivalReadiness needs = new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.NORMAL, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "203", ROOM)));
        ArrivalReadiness ok = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of());
        when(queryService.arrivals()).thenReturn(List.of(
                new FrontDeskArrivalRow(RES, "R-1028", "Tran Minh", "G-00141", BookingSource.DIRECT, null, TODAY,
                        false, true, needs, List.of(dirty), true),
                new FrontDeskArrivalRow(UUID.randomUUID(), "R-1030", "John Smith", "G-00152", BookingSource.BOOKING_COM,
                        "BK-77", TODAY, false, false, ok,
                        List.of(new FrontDeskRoomResponse(UUID.randomUUID(), "301", "Twin", null),
                                new FrontDeskRoomResponse(UUID.randomUUID(), "302", "Twin", null)), false)));
        List<FrontDeskRoomResponse> rooms = List.of(new FrontDeskRoomResponse(ROOM, "101", "Single", null));
        when(queryService.departures(false)).thenReturn(List.of(stayRow(rooms, true, true, null)));
        when(queryService.departures(true)).thenReturn(List.of(stayRow(rooms, true, true, new BigDecimal("300000"))));
        when(queryService.inHouse()).thenReturn(List.of(stayRow(rooms, false, false, null)));
    }

    /** Confirms CHECK_IN alone opens Front Desk on Arrivals and shows no Departures/In-house tab or data. */
    @Test
    void shouldShowOnlyArrivalsToCheckInUser() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"tab-arrivals\"")))
                .andExpect(content().string(not(containsString("id=\"tab-departures\""))))
                .andExpect(content().string(not(containsString("id=\"tab-in-house\""))))
                .andExpect(content().string(containsString("R-1028")));
        verify(queryService, never()).departures(anyBoolean());
        verify(queryService, never()).inHouse();
    }

    /** Confirms CHECK_OUT alone opens Front Desk on Departures with no Arrivals tab or data. */
    @Test
    void shouldShowOnlyDeparturesAndInHouseToCheckOutUser() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"tab-arrivals\""))))
                .andExpect(content().string(containsString("id=\"tab-departures\"")))
                .andExpect(content().string(containsString("id=\"tab-in-house\"")))
                .andExpect(content().string(containsString("Overdue Departure")));
        verify(queryService, never()).arrivals();
    }

    /** Confirms a user with neither permission gets 403. */
    @Test
    void shouldRefuseUserWithoutCheckInOrCheckOut() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_PAYMENT", "PERM_MANAGE_HOUSEKEEPING")))
                .andExpect(status().isForbidden());
    }

    /** Confirms supplying another tab's view parameter cannot fetch its data. */
    @Test
    void shouldRefuseUnauthorizedViewParameter() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_IN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/front-desk").param("view", "arrivals").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isForbidden());
        verify(queryService, never()).departures(anyBoolean());
        verify(queryService, never()).inHouse();
        verify(queryService, never()).arrivals();
    }

    /** Confirms an unknown view falls back to the first authorized one. */
    @Test
    void shouldFallBackToAnAuthorizedDefaultView() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "bogus").with(perm("PERM_CHECK_OUT")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"departures-attention\"")));
    }

    /** Confirms the sidebar Front Desk link follows CHECK_IN OR CHECK_OUT. */
    @Test
    void shouldShowSidebarEntryForCheckInOrCheckOut() throws Exception {
        for (String permission : List.of("PERM_CHECK_IN", "PERM_CHECK_OUT")) {
            mockMvc.perform(get("/front-desk").with(perm(permission)))
                    .andExpect(content().string(containsString("href=\"/front-desk\"")));
        }
    }

    /** Confirms arrival actions follow each existing permission (housekeeping link, reservation detail). */
    @Test
    void shouldGateArrivalActionsByTheirOwnPermissions() throws Exception {
        String review = "/check-in/reservations/" + RES;
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString(review)))
                .andExpect(content().string(not(containsString("href=\"/housekeeping\""))))
                .andExpect(content().string(not(containsString("href=\"/reservations/" + RES + "\""))));
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN", "PERM_MANAGE_HOUSEKEEPING", "PERM_VIEW_BOOKING")))
                .andExpect(content().string(containsString("href=\"/housekeeping\"")))
                .andExpect(content().string(containsString("href=\"/reservations/" + RES + "\"")));
    }

    /** Confirms a ready multi-room arrival is one row with both rooms, and a blocked room shows its issue. */
    @Test
    void shouldRenderArrivalRowsWithRoomsAndIssues() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")))
                .andExpect(content().string(containsString("Room 203 needs cleaning")))
                .andExpect(content().string(containsString("301 Twin")))
                .andExpect(content().string(containsString("302 Twin")))
                .andExpect(content().string(containsString("Tran Minh")))
                .andExpect(content().string(containsString("G-00141")))
                .andExpect(content().string(containsString("Check in")))
                .andExpect(content().string(containsString("BK-77")));
    }

    /** Confirms no amount is fetched-as-visible or rendered without MANAGE_PAYMENT, and it is with it. */
    @Test
    void shouldControlBalanceAmountByManagePayment() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("Payment Required")))
                .andExpect(content().string(not(containsString("300"))))
                .andExpect(content().string(not(containsString("/folio"))));
        verify(queryService).departures(false);
        mockMvc.perform(get("/front-desk").param("view", "departures").with(perm("PERM_CHECK_OUT", "PERM_MANAGE_PAYMENT")))
                .andExpect(content().string(containsString("300")))
                .andExpect(content().string(containsString("/reservations/" + RES + "/folio")));
        verify(queryService).departures(true);
    }

    /** Confirms In-house shows current rooms, hotel-time check-in, and Change Room only with CHANGE_ROOM. */
    @Test
    void shouldRenderInHouseWithChangeRoomOnlyWhenPermitted() throws Exception {
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT")))
                .andExpect(content().string(containsString("101 Single")))
                .andExpect(content().string(containsString("20/09/2026 14:35")))
                .andExpect(content().string(not(containsString("/change"))));
        mockMvc.perform(get("/front-desk").param("view", "in-house").with(perm("PERM_CHECK_OUT", "PERM_CHANGE_ROOM")))
                .andExpect(content().string(containsString("/reservations/" + RES + "/rooms/" + ROOM + "/change")));
    }

    /** Confirms Vietnamese labels are used when Vietnamese is selected. */
    @Test
    void shouldRenderVietnamese() throws Exception {
        mockMvc.perform(get("/front-desk").with(perm("PERM_CHECK_IN")).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Khách đến")))
                .andExpect(content().string(containsString("Cần xử lý")))
                .andExpect(content().string(containsString("Nhận phòng")));
    }

    private static FrontDeskStayRow stayRow(List<FrontDeskRoomResponse> rooms, boolean overdue, boolean owing, BigDecimal amount) {
        return new FrontDeskStayRow(RES, "R-9", "Nguyen Van B", "G-1", rooms, Instant.parse("2026-09-20T07:35:00Z"),
                TODAY.minusDays(overdue ? 1 : 0), overdue, owing, overdue || owing, amount, "VND");
    }

    private static RequestPostProcessor perm(String... authorities) {
        return user("tester").authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
