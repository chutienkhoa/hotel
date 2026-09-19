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
     * Confirms the Room filter is handed to the shared database query as a CURRENT-room predicate
     * (with the original booked-room filter cleared), so it filters and counts before pagination
     * and no Java post-page filtering remains. Room Change and multi-room semantics are verified
     * against real PostgreSQL in {@code ReservationCurrentRoomSearchIntegrationTest}.
     */
    @Test
    void shouldDelegateCurrentRoomFilterToDatabaseBeforePagination() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        StayQueryService stayQueryService = mock(StayQueryService.class);
        StayRoomAssignmentQueryService stayRoomAssignmentQueryService = mock(StayRoomAssignmentQueryService.class);
        StayBalanceService stayBalanceService = mock(StayBalanceService.class);
        List<String> seenAtQueryTime = new java.util.ArrayList<>();
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(1))).thenAnswer(invocation -> {
            ReservationSearchCriteria seen = invocation.getArgument(0);
            seenAtQueryTime.add(seen.getCurrentRoom() + "|" + seen.getRoom() + "|" + seen.getStatus());
            return new PageImpl<>(List.of(summary()), PageRequest.of(1, 10), 21);
        });
        when(reservationQueryService.findById(RESERVATION_ID)).thenReturn(detail("CHECKED_IN"));
        when(stayQueryService.findByReservationId(RESERVATION_ID)).thenReturn(stay());
        when(stayRoomAssignmentQueryService.findCurrentRooms(RESERVATION_ID)).thenReturn(List.of(currentRoom("305")));
        when(stayBalanceService.calculate(STAY_ID)).thenReturn(balance(BigDecimal.ZERO));
        ReservationSearchCriteria criteria = new ReservationSearchCriteria();
        criteria.setRoom("305");

        org.springframework.data.domain.Page<CheckOutListItemResponse> result =
                service(reservationQueryService, stayQueryService, stayRoomAssignmentQueryService, stayBalanceService)
                        .search(criteria, 1);

        assertEquals(List.of("305|null|CHECKED_IN"), seenAtQueryTime);
        assertEquals(21, result.getTotalElements(), "the total must come from the database count, not the page");
        assertEquals(3, result.getTotalPages());
        assertEquals(1, result.getContent().size());
        assertEquals(1, result.getNumber());
    }

    /** Confirms only Check-out sort keys are honoured; other Reservation sort keys fall back to the default. */
    @Test
    void shouldRestrictSortToCheckOutKeys() {
        ReservationQueryService reservationQueryService = mock(ReservationQueryService.class);
        when(reservationQueryService.findPage(any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        CheckOutQueryService service = service(
                reservationQueryService, mock(StayQueryService.class), mock(StayRoomAssignmentQueryService.class),
                mock(StayBalanceService.class));

        ReservationSearchCriteria allowed = new ReservationSearchCriteria();
        allowed.setSort("checkOutDate");
        allowed.setDir("desc");
        service.search(allowed, 0);
        assertEquals("checkOutDate", allowed.getSort());

        ReservationSearchCriteria foreign = new ReservationSearchCriteria();
        foreign.setSort("status");
        foreign.setDir("asc");
        service.search(foreign, 0);
        assertEquals(null, foreign.getSort());
        assertEquals(null, foreign.getDir());
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
