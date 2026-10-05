package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.controller.common.NavigationModelAdvice;
import com.example.hotel.dto.booking.request.ReservationListCriteria;
import com.example.hotel.dto.booking.response.ReservationListRoomResponse;
import com.example.hotel.dto.booking.response.ReservationListRowResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.ChargeService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PaymentService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationDetailEligibilityService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the Task33 Reservation List page: table content and columns, localized status/source, row action, sortable
 * headers, filter/sort/pagination link preservation, Stay date validation, empty states, EN/VI and permissions.
 */
@WebMvcTest(ReservationPageController.class)
@Import({ReservationListPageTest.MethodSecurityTestConfiguration.class, NavigationModelAdvice.class, I18nConfig.class})
class ReservationListPageTest {

    private static final UUID FIRST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private ReservationDetailEligibilityService detailEligibilityService;

    @MockitoBean
    private JwtService jwtService;

    // ---- table content ----------------------------------------------------------------------------------------

    /** Confirms every approved column, the View action, localized values and the multi-room structure render. */
    @Test
    void shouldRenderApprovedColumnsRowsAndSingleViewAction() throws Exception {
        stubRows();

        String body = mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Reservation No.<")))
                .andExpect(content().string(containsString(">Guest<")))
                .andExpect(content().string(containsString(">Stay<")))
                .andExpect(content().string(containsString(">Room(s)<")))
                .andExpect(content().string(containsString(">Nights<")))
                .andExpect(content().string(containsString(">Source<")))
                .andExpect(content().string(containsString(">OTA Ref<")))
                .andExpect(content().string(containsString(">Status<")))
                .andExpect(content().string(containsString(">Action<")))
                .andExpect(content().string(containsString("R20261004-000001")))
                .andExpect(content().string(containsString("Nguyen Van A")))
                .andExpect(content().string(containsString("G000001")))
                .andExpect(content().string(containsString("04/10/2026")))
                .andExpect(content().string(containsString("08/10/2026")))
                .andExpect(content().string(containsString(">201<")))
                .andExpect(content().string(containsString(">Double Room<")))
                .andExpect(content().string(containsString(">305<")))
                .andExpect(content().string(containsString(">Twin Room<")))
                .andExpect(content().string(containsString(">Booking.com<")))
                .andExpect(content().string(containsString("0192334455")))
                .andExpect(content().string(containsString(">Confirmed<")))
                .andExpect(content().string(containsString(">Checked In<")))
                .andExpect(content().string(containsString("status-badge--confirmed")))
                .andExpect(content().string(containsString("status-badge--draft")))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("<td class=\"reservation-list-nights\">4</td>"));
        assertTrue(body.contains("<td class=\"reservation-list-nights\">3</td>"));
        // Localized labels replace the raw enum names and the English-only displayName.
        assertTrue(!body.contains(">CHECKED_IN<") && !body.contains(">CONFIRMED<") && !body.contains(">BOOKING_COM<"));
        // The legacy Total column is gone.
        assertTrue(!body.contains(">Total<"));
        // Exactly one action per row: a View link to the real detail route, nothing else.
        assertEquals(2, count(body, "reservation-list-action"));
        assertTrue(body.contains("href=\"/reservations/" + FIRST_ID + "\""));
        assertTrue(body.contains("aria-label=\"View reservation R20261004-000001\""));
        for (String forbidden : List.of("/edit", "/confirm", "/cancel", "/no-show", "/check-in", "/check-out")) {
            assertTrue(!body.contains("/reservations/" + FIRST_ID + forbidden), forbidden);
        }
        // A DIRECT reservation (no OTA reference) shows a dash.
        assertTrue(body.contains(">—<"));
    }

    /** Confirms the multi-room reservation renders every room, in order, never just the first. */
    @Test
    void shouldRenderEveryRoomOfAMultiRoomReservation() throws Exception {
        stubRows();

        String body = mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(2, count(body, "class=\"reservation-list-room\""));
        assertTrue(body.indexOf(">201<") < body.indexOf(">305<"));
    }

    /** Confirms the table, header and filters are localized in Vietnamese, including status and source labels. */
    @Test
    void shouldRenderVietnameseLabels() throws Exception {
        stubRows();

        mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING"))
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Đặt phòng mới<")))
                .andExpect(content().string(containsString(">Tìm kiếm<")))
                .andExpect(content().string(containsString("Tất cả trạng thái")))
                .andExpect(content().string(containsString("Tất cả nguồn")))
                .andExpect(content().string(containsString(">Mã đặt phòng<")))
                .andExpect(content().string(containsString(">Số đêm<")))
                .andExpect(content().string(containsString(">Đã xác nhận<")))
                .andExpect(content().string(containsString(">Đã nhận phòng<")))
                .andExpect(content().string(containsString(">Xem<")))
                .andExpect(content().string(not(containsString(">Reservation No.<"))));
    }

    // ---- header / permissions ---------------------------------------------------------------------------------

    /** Confirms New Reservation links to the real create route only for a user who can manage bookings. */
    @Test
    void shouldShowNewReservationOnlyWithManageBookingPermission() throws Exception {
        stubRows();

        mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/reservations/new\"")))
                .andExpect(content().string(containsString("New Reservation")))
                .andExpect(content().string(containsString("Manage hotel reservations across all booking sources.")));

        mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/reservations/new\""))));
    }

    /** Confirms the list is denied without VIEW_BOOKING and the create route stays protected by MANAGE_BOOKING. */
    @Test
    void shouldProtectListAndCreateRoutesOnTheBackend() throws Exception {
        mockMvc.perform(get("/reservations").with(perm("PERM_MANAGE_EXPENSE"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/reservations/new").with(perm("PERM_VIEW_BOOKING"))).andExpect(status().isForbidden());
        verifyNoInteractions(reservationQueryService);
    }

    // ---- filters ----------------------------------------------------------------------------------------------

    /** Confirms the filter bar is exactly Search, Stay date, Status, Source and Clear, with no legacy date inputs. */
    @Test
    void shouldRenderApprovedFilterBarWithoutLegacyControls() throws Exception {
        stubRows();

        String body = mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"search\"")))
                .andExpect(content().string(containsString("Reservation No., guest name, room no., OTA reference...")))
                .andExpect(content().string(containsString(">Stay date<")))
                .andExpect(content().string(containsString(">All dates<")))
                .andExpect(content().string(containsString("name=\"stayFrom\"")))
                .andExpect(content().string(containsString("name=\"stayTo\"")))
                .andExpect(content().string(containsString(">All status<")))
                .andExpect(content().string(containsString(">All sources<")))
                .andExpect(content().string(containsString("href=\"/reservations\"")))
                .andExpect(content().string(containsString("src=\"/js/reservation/list-filters.js\"")))
                .andExpect(content().string(containsString("href=\"/css/reservation/list.css\"")))
                .andReturn().getResponse().getContentAsString();

        for (String legacy : List.of("checkInFrom", "checkInTo", "checkOutFrom", "checkOutTo", "name=\"reservationNumber\"",
                "name=\"guest\"", "name=\"room\"", "name=\"otaBookingReference\"", "js-date-picker")) {
            assertTrue(!body.contains(legacy), legacy);
        }
    }

    /** Confirms Status and Source offer exactly the real enum values with localized labels. */
    @Test
    void shouldOfferEveryRealStatusAndSource() throws Exception {
        stubRows();

        String body = mockMvc.perform(get("/reservations").param("status", "NO_SHOW").param("source", "AIRBNB")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED", "NO_SHOW", "CHECKED_IN", "CHECKED_OUT")) {
            assertTrue(body.contains("<option value=\"" + status + "\""), status);
        }
        for (String source : List.of("DIRECT", "AGODA", "BOOKING_COM", "AIRBNB")) {
            assertTrue(body.contains("<option value=\"" + source + "\""), source);
        }
        assertTrue(body.replaceAll("\\s+", " ").contains("value=\"NO_SHOW\" selected=\"selected\""));
        assertTrue(body.replaceAll("\\s+", " ").contains("value=\"AIRBNB\" selected=\"selected\""));
        assertTrue(body.contains(">No Show<") && body.contains(">Checked Out<") && body.contains(">Airbnb<"));
        for (String presentation : List.of("Upcoming", "Active", "Completed", "Pending")) {
            assertTrue(!body.contains(">" + presentation + "<"), presentation);
        }
    }

    /** Confirms the submitted filters bind to the list criteria (search trimmed, ISO dates, enums). */
    @Test
    void shouldBindFiltersToCriteria() throws Exception {
        stubRows();

        mockMvc.perform(get("/reservations").param("search", "  Ann Lee  ").param("stayFrom", "2026-10-04")
                        .param("stayTo", "2026-10-10").param("status", "CONFIRMED").param("source", "AGODA")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Ann Lee\"")))
                .andExpect(content().string(containsString("04/10/2026 → 10/10/2026")))
                .andExpect(content().string(containsString("data-stay-from value=\"2026-10-04\"")));

        ArgumentCaptor<ReservationListCriteria> captor = ArgumentCaptor.forClass(ReservationListCriteria.class);
        verify(reservationQueryService).findListPage(captor.capture(), eq(0));
        assertEquals("Ann Lee", captor.getValue().getSearch());
        assertEquals(LocalDate.of(2026, 10, 4), captor.getValue().getStayFrom());
        assertEquals(LocalDate.of(2026, 10, 10), captor.getValue().getStayTo());
        assertEquals(ReservationStatus.CONFIRMED, captor.getValue().getStatus());
        assertEquals(BookingSource.AGODA, captor.getValue().getSource());
    }

    /** Confirms blank filters mean "no filter": nothing is bound and the picker shows All dates. */
    @Test
    void shouldTreatBlankFiltersAsAbsent() throws Exception {
        stubRows();

        mockMvc.perform(get("/reservations").param("search", "   ").param("stayFrom", "").param("stayTo", "")
                        .param("status", "").param("source", "").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">All dates<")));

        ArgumentCaptor<ReservationListCriteria> captor = ArgumentCaptor.forClass(ReservationListCriteria.class);
        verify(reservationQueryService).findListPage(captor.capture(), eq(0));
        assertNull(captor.getValue().getSearch());
        assertNull(captor.getValue().getStayFrom());
        assertNull(captor.getValue().getStayTo());
        assertNull(captor.getValue().getStatus());
        assertNull(captor.getValue().getSource());
    }

    // ---- Stay date validation ---------------------------------------------------------------------------------

    /** Confirms a one-sided Stay date range is rejected server-side with a localized message and no query. */
    @Test
    void shouldRejectOneSidedStayRange() throws Exception {
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-10-04").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose both a check-in and a check-out date")));
        mockMvc.perform(get("/reservations").param("stayTo", "2026-10-10").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose both a check-in and a check-out date")));
        verifyNoInteractions(reservationQueryService);
    }

    /** Confirms start after end and start equal to end are both rejected, because the range is half-open. */
    @Test
    void shouldRejectReversedAndEmptyStayRange() throws Exception {
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-10-10").param("stayTo", "2026-10-04")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The stay date range must end after it starts.")));
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-10-04").param("stayTo", "2026-10-04")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The stay date range must end after it starts.")));
        verifyNoInteractions(reservationQueryService);
    }

    /** Confirms a range over one calendar year is rejected while exactly one year is accepted. */
    @Test
    void shouldEnforceMaximumOfOneCalendarYear() throws Exception {
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-01-01").param("stayTo", "2027-01-02")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The stay date range cannot exceed one calendar year.")))
                .andExpect(content().string(not(containsString("Reservation pages"))));
        verifyNoInteractions(reservationQueryService);

        stubRows();
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-01-01").param("stayTo", "2027-01-01")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("cannot exceed one calendar year"))));
    }

    /** Confirms validation messages are localized and malformed values give the generic localized message. */
    @Test
    void shouldLocalizeValidationAndRejectMalformedValues() throws Exception {
        mockMvc.perform(get("/reservations").param("stayFrom", "2026-10-04").with(perm("PERM_VIEW_BOOKING"))
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hãy chọn cả ngày nhận phòng và ngày trả phòng")));
        mockMvc.perform(get("/reservations").param("stayFrom", "not-a-date").param("stayTo", "2026-10-10")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please provide valid reservation filter values.")));
        mockMvc.perform(get("/reservations").param("status", "UPCOMING").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please provide valid reservation filter values.")));
        verifyNoInteractions(reservationQueryService);
    }

    // ---- sorting ----------------------------------------------------------------------------------------------

    /** Confirms only the six approved columns are sortable and Room(s), Nights and Action are plain headers. */
    @Test
    void shouldRenderSortLinksOnlyForApprovedColumns() throws Exception {
        stubRows();

        String body = mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(6, count(body, "class=\"reservation-list-sort\""));
        for (String key : List.of("reservationNumber", "guest", "checkInDate", "source", "otaBookingReference", "status")) {
            assertTrue(body.contains("sort=" + key + "&amp;dir=asc"), key);
        }
        assertTrue(body.contains("<th scope=\"col\">Room(s)</th>"));
        assertTrue(body.contains("<th scope=\"col\">Nights</th>"));
        assertTrue(body.contains("<th class=\"table-action-column\" scope=\"col\">Action</th>"));
    }

    /** Confirms sort links keep filters and drop the page, page links keep filters and sort. */
    @Test
    void shouldPreserveFiltersAndSortAcrossSortAndPageLinks() throws Exception {
        when(reservationQueryService.findListPage(any(), eq(1)))
                .thenReturn(new PageImpl<>(List.of(row(FIRST_ID, ReservationStatus.CONFIRMED)), PageRequest.of(1, 10), 25));

        String body = mockMvc.perform(get("/reservations").param("page", "1").param("search", "Ann")
                        .param("stayFrom", "2026-10-04").param("stayTo", "2026-10-10").param("status", "CONFIRMED")
                        .param("source", "AGODA").param("sort", "checkInDate").param("dir", "asc")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-sort=\"ascending\"")))
                .andExpect(content().string(containsString("name=\"sort\" value=\"checkInDate\"")))
                .andExpect(content().string(containsString("Showing 11–11 of 25")))
                .andReturn().getResponse().getContentAsString();

        String filters = "search=Ann&amp;stayFrom=2026-10-04&amp;stayTo=2026-10-10&amp;status=CONFIRMED&amp;source=AGODA";
        assertTrue(body.contains("href=\"/reservations?" + filters + "&amp;sort=reservationNumber&amp;dir=asc\""));
        assertTrue(body.contains("href=\"/reservations?" + filters + "&amp;sort=checkInDate&amp;dir=desc\""));
        assertTrue(body.contains("href=\"/reservations?" + filters + "&amp;sort=checkInDate&amp;dir=asc&amp;page=0\""));
        assertTrue(!body.contains("sort=reservationNumber&amp;dir=asc&amp;page"));
    }

    /** Confirms an unknown (or removed) sort key, unknown direction and malformed page fall back safely. */
    @Test
    void shouldFallBackSafelyForUnsupportedSortAndMalformedPage() throws Exception {
        stubRows();

        for (String unsupported : List.of("password_hash; DROP", "checkOutDate", "nights", "room")) {
            mockMvc.perform(get("/reservations").param("page", "abc").param("sort", unsupported).param("dir", "asc")
                            .with(perm("PERM_VIEW_BOOKING")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("aria-sort=\"ascending\""))))
                    .andExpect(content().string(not(containsString("aria-sort=\"descending\""))))
                    .andExpect(content().string(not(containsString("name=\"sort\""))));
        }
        mockMvc.perform(get("/reservations").param("sort", "status").param("dir", "sideways").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"sort\""))));
    }

    // ---- pagination and empty states --------------------------------------------------------------------------

    /** Confirms the pager sits in the results header above the table and disables the unavailable direction. */
    @Test
    void shouldRenderPagerAboveTableWithDisabledPrevious() throws Exception {
        when(reservationQueryService.findListPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(row(FIRST_ID, ReservationStatus.CONFIRMED)), PageRequest.of(0, 10), 500));

        String body = mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Showing 1–1 of 500")))
                .andExpect(content().string(containsString("pagination__segment--current")))
                .andExpect(content().string(containsString("pagination__segment--disabled")))
                .andExpect(content().string(containsString("page=1")))
                .andExpect(content().string(containsString("page=49")))
                .andExpect(content().string(containsString(">...</span>")))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.indexOf("reservation-list-pagination") < body.indexOf("<table"));
    }

    /** Confirms a page past the last redirects to the last valid page, preserving every filter and the sort. */
    @Test
    void shouldRedirectOutOfRangePagePreservingFilters() throws Exception {
        when(reservationQueryService.findListPage(any(), eq(999)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(999, 10), 25));

        mockMvc.perform(get("/reservations").param("page", "999").param("search", "Ann Lee")
                        .param("stayFrom", "2026-10-04").param("stayTo", "2026-10-10").param("status", "CONFIRMED")
                        .param("source", "AGODA").param("sort", "guest").param("dir", "desc")
                        .with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations?search=Ann+Lee&stayFrom=2026-10-04&stayTo=2026-10-10"
                        + "&status=CONFIRMED&source=AGODA&sort=guest&dir=desc&page=2"));
    }

    /** Confirms a truly empty result never redirects and offers New Reservation only to a user who can create one. */
    @Test
    void shouldShowTrueEmptyStateWithPermissionAwareAction() throws Exception {
        when(reservationQueryService.findListPage(any(), eq(5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 0));

        mockMvc.perform(get("/reservations").param("page", "5").with(perm("PERM_VIEW_BOOKING", "PERM_MANAGE_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No reservations yet.")))
                .andExpect(content().string(containsString("empty-state-message")))
                .andExpect(content().string(not(containsString("No reservations match the current filters."))))
                .andExpect(content().string(not(containsString("<table"))));

        String viewOnly = mockMvc.perform(get("/reservations").param("page", "5").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No reservations yet.")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(!viewOnly.contains("href=\"/reservations/new\""));
    }

    /** Confirms filtered-empty shows the no-results state with a working Clear filters link. */
    @Test
    void shouldShowNoResultsStateWhenFiltersMatchNothing() throws Exception {
        when(reservationQueryService.findListPage(any(), eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/reservations").param("search", "zzz").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No reservations match the current filters.")))
                .andExpect(content().string(containsString("Clear filters")))
                .andExpect(content().string(not(containsString("No reservations yet."))))
                .andExpect(content().string(containsString("0 results")));
    }

    /** Keeps the shared navigation class so the Reservations item stays active. */
    @Test
    void shouldKeepReservationsNavigationActive() throws Exception {
        stubRows();

        mockMvc.perform(get("/reservations").with(perm("PERM_VIEW_BOOKING")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell page-reservations")))
                .andExpect(content().string(containsString("nav-reservations")));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void stubRows() {
        when(reservationQueryService.findListPage(any(), any(Integer.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new ReservationListRowResponse(FIRST_ID, "R20261004-000001", "Nguyen Van A", "G000001",
                                LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 8), 4,
                                List.of(new ReservationListRoomResponse("201", "Double Room"),
                                        new ReservationListRoomResponse("305", "Twin Room")),
                                BookingSource.BOOKING_COM, "0192334455", ReservationStatus.CONFIRMED),
                        new ReservationListRowResponse(SECOND_ID, "R20261002-000002", "Tran Thi B", "G000002",
                                LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 9), 3, List.of(),
                                BookingSource.DIRECT, null, ReservationStatus.DRAFT)),
                        PageRequest.of(0, 10), 2));
    }

    private ReservationListRowResponse row(UUID id, ReservationStatus status) {
        return new ReservationListRowResponse(id, "R20261004-000001", "Nguyen Van A", "G000001",
                LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 8), 4,
                List.of(new ReservationListRoomResponse("201", "Double Room")), BookingSource.AGODA, "REF-1", status);
    }

    private static int count(String body, String token) {
        int count = 0;
        for (int index = body.indexOf(token); index >= 0; index = body.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    private static RequestPostProcessor perm(String... authorities) {
        return user("tester").authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
