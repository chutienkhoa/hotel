package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.CheckOutListItemResponse;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Verifies the Check-out operational search and Review reads without duplicating business logic. */
class CheckOutQueryServiceTest {

    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final UUID STAY_ID = UUID.randomUUID();
    private static final UUID ROOM_ID = UUID.randomUUID();

    /** Confirms search forces the CHECKED_IN status filter regardless of caller-supplied criteria. */
    @Test
    void shouldForceCheckedInStatusFilterOnSearch() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(criteria, 0);

        assertEquals(ReservationStatus.CHECKED_IN, criteria.getStatus());
    }

    /** Confirms each list row uses the Stay's current rooms, not the original ReservationRoom. */
    @Test
    void shouldEnrichListItemWithCurrentRoomsAndReadyReadiness() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        ReservationSummaryResponse summary = summary();
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(
                List.of(currentRoom("305"), currentRoom("202")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));

        List<CheckOutListItemResponse> items = service(
                reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(new ReservationSearchCriteria(), 0)
                .getContent();

        assertEquals(1, items.size());
        assertEquals("305, 202", items.get(0).currentRoomNumbers());
        assertEquals("READY", items.get(0).readiness());
        assertEquals("GUEST-001", items.get(0).guestCode());
    }

    /** Confirms a non-zero Outstanding produces PAYMENT_REQUIRED readiness, never a monetary amount. */
    @Test
    void shouldReportPaymentRequiredForNonZeroOutstanding() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 10), 1));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(new BigDecimal("100000")));

        CheckOutListItemResponse item = service(
                reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(new ReservationSearchCriteria(), 0)
                .getContent()
                .get(0);

        assertEquals("PAYMENT_REQUIRED", item.readiness());
    }

    /** Confirms the Review response uses current rooms and marks eligibility only when CHECKED_IN and READY. */
    @Test
    void shouldBuildReviewEligibleOnlyWhenCheckedInAndReady() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));

        CheckOutReviewResponse review = service(
                        reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .review(RESERVATION_ID);

        assertTrue(review.eligibleForCheckOut());
        assertEquals("READY", review.readiness());
        assertEquals(1, review.currentRooms().size());
        assertEquals("305", review.currentRooms().get(0).roomNumber());
        assertEquals(GUEST_ID, review.guestId());
        assertEquals("GUEST-001", review.guestCode());
    }

    /** Confirms Review is never eligible when Outstanding is non-zero, regardless of Reservation status. */
    @Test
    void shouldMarkReviewNotEligibleWhenPaymentRequired() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(new BigDecimal("50000")));

        CheckOutReviewResponse review = service(
                        reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .review(RESERVATION_ID);

        assertFalse(review.eligibleForCheckOut());
        assertEquals("PAYMENT_REQUIRED", review.readiness());
    }

    /** Confirms Review is never eligible once the Reservation is no longer CHECKED_IN, even if READY. */
    @Test
    void shouldMarkReviewNotEligibleWhenReservationNoLongerCheckedIn() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_OUT"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of());
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));

        CheckOutReviewResponse review = service(
                        reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .review(RESERVATION_ID);

        assertFalse(review.eligibleForCheckOut());
        assertEquals("CHECKED_OUT", review.status());
    }

    /**
     * Confirms Room search matches the Stay's CURRENT open StayRoomAssignment room, not the
     * original ReservationRoom — after a Room Change, searching the replacement room (305) must
     * find the stay, and searching the released original room (201) must not.
     */
    @Test
    void shouldMatchCurrentRoomAfterRoomChangeNotOriginalRoom() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 10), 1));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));
        CheckOutQueryService service =
                service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService);

        ReservationSearchCriteria matchingCurrentRoom = new ReservationSearchCriteria();
        matchingCurrentRoom.setRoom("305");
        List<CheckOutListItemResponse> matched = service.search(matchingCurrentRoom, 0).getContent();
        assertEquals(1, matched.size());
        assertEquals("305", matched.get(0).currentRoomNumbers());

        ReservationSearchCriteria searchingReleasedOriginalRoom = new ReservationSearchCriteria();
        searchingReleasedOriginalRoom.setRoom("201");
        List<CheckOutListItemResponse> notMatched = service.search(searchingReleasedOriginalRoom, 0).getContent();
        assertTrue(notMatched.isEmpty(), "the released original room must no longer find the stay");
    }

    /** Confirms a multi-room stay's current-room search matches on any of its current rooms. */
    @Test
    void shouldMatchMultiRoomStayByAnyCurrentRoom() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 10), 1));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID))
                .thenReturn(List.of(currentRoom("305"), currentRoom("202")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        criteria.setRoom("202");

        List<CheckOutListItemResponse> matched =
                service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                        .search(criteria, 0)
                        .getContent();

        assertEquals(1, matched.size());
        assertEquals("305, 202", matched.get(0).currentRoomNumbers());
    }

    /** Confirms the caller's Room filter value is preserved after search, so a re-rendered form keeps it. */
    @Test
    void shouldPreserveRoomFilterValueOnCriteriaAfterSearch() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        criteria.setRoom("305");

        service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(criteria, 0);

        assertEquals("305", criteria.getRoom());
    }

    /** Confirms Reservation Number and Guest filters still pass through to the shared search. */
    @Test
    void shouldForwardReservationNumberAndGuestFiltersToSharedSearch() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        criteria.setReservationNumber("R20260917-000009");
        criteria.setGuest("DEMO-G013");

        ArgumentCaptor<ReservationSearchCriteria> captor = ArgumentCaptor.forClass(ReservationSearchCriteria.class);
        service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(criteria, 0);

        verify(reservationQueryService).findPage(captor.capture(), org.mockito.ArgumentMatchers.eq(0));
        assertEquals("R20260917-000009", captor.getValue().getReservationNumber());
        assertEquals("DEMO-G013", captor.getValue().getGuest());
    }

    /** Confirms search never queries Outstanding amounts, only the non-financial readiness label. */
    @Test
    void shouldNeverExposeMonetaryAmountsFromSearch() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 10), 1));
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(new BigDecimal("100000")));

        List<String> components = java.util.Arrays.stream(CheckOutListItemResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertFalse(components.contains("totalCharges"));
        assertFalse(components.contains("totalPaid"));
        assertFalse(components.contains("outstanding"));

        ArgumentCaptor<UUID> stayIdCaptor = ArgumentCaptor.forClass(UUID.class);
        service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                .search(new ReservationSearchCriteria(), 0);
        verify(stayBalanceService).calculate(stayIdCaptor.capture());
        assertEquals(STAY_ID, stayIdCaptor.getValue());
    }

    /** Creates the query service under test. */
    private CheckOutQueryService service(
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            StayRoomAssignmentQueryService stayRoomAssignmentQueryService,
            StayBalanceService stayBalanceService) {
        return new CheckOutQueryService(
                reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService);
    }

    private ReservationSummaryResponse summary() {
        return new ReservationSummaryResponse(
                RESERVATION_ID,
                "R20260917-000009",
                "Nguyen Van A",
                "305, 202",
                "CHECKED_IN",
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 19),
                BigDecimal.TEN,
                "VND");
    }

    private ReservationDetailResponse detail(String status) {
        return new ReservationDetailResponse(
                RESERVATION_ID,
                "R20260917-000009",
                GUEST_ID,
                "GUEST-001",
                status,
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 19),
                BigDecimal.TEN,
                "VND",
                null,
                List.of());
    }

    private StayResponse stay() {
        return new StayResponse(STAY_ID, "CHECKED_IN", Instant.parse("2026-09-17T10:00:00Z"), null);
    }

    private CurrentRoomResponse currentRoom(String roomNumber) {
        return new CurrentRoomResponse(UUID.randomUUID(), ROOM_ID, roomNumber, Instant.parse("2026-09-17T10:00:00Z"));
    }

    private StayBalance balance(BigDecimal outstanding) {
        return new StayBalance(outstanding, BigDecimal.ZERO, outstanding);
    }
}
