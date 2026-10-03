package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.common.response.DashboardPermissions;
import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the final, operational-first Dashboard V1 read model (spec sec. 9.1). */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate HOTEL_TODAY = LocalDate.of(2026, 9, 16);

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private StayRepository stayRepository;

    @Mock
    private FrontDeskQueryService frontDeskQueryService;

    @Mock
    private ReservationQueryService reservationQueryService;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"), HOTEL_ZONE);
        dashboardService =
                new DashboardService(roomRepository, stayRepository, frontDeskQueryService, reservationQueryService, clock);
        lenient().when(roomRepository.countActiveByStatus()).thenReturn(List.of(
                row(RoomStatus.AVAILABLE, 6L), row(RoomStatus.OCCUPIED, 12L), row(RoomStatus.DIRTY, 1L)));
        lenient().when(roomRepository.countByActiveTrue()).thenReturn(20L);
    }

    /** Verifies the Dashboard service has no dependency on financial domain components. */
    @Test
    void shouldNotDependOnFinancialDomainComponents() {
        assertFalse(List.of(DashboardService.class.getDeclaredFields()).stream()
                .map(field -> field.getType().getSimpleName())
                .anyMatch(name -> name.contains("Charge")
                        || name.contains("Payment")
                        || name.contains("Balance")
                        || name.contains("Expense")));
    }

    /** Verifies Room Status is always zero-filled across all 6 statuses, even absent ones, and totals match. */
    @Test
    void shouldReturnZeroFilledRoomStatusAcrossAllSixStatuses() {
        DashboardResponse dashboard = dashboardService.getDashboard(noOperationalPermissions());

        assertEquals(6, dashboard.roomsByStatus().size());
        assertEquals("AVAILABLE", dashboard.roomsByStatus().get(0).status());
        assertEquals(6L, dashboard.roomsByStatus().get(0).count());
        assertEquals("OCCUPIED", dashboard.roomsByStatus().get(1).status());
        assertEquals(12L, dashboard.roomsByStatus().get(1).count());
        assertEquals("DIRTY", dashboard.roomsByStatus().get(2).status());
        assertEquals(1L, dashboard.roomsByStatus().get(2).count());
        assertEquals("CLEANING", dashboard.roomsByStatus().get(3).status());
        assertEquals(0L, dashboard.roomsByStatus().get(3).count());
        assertEquals("MAINTENANCE", dashboard.roomsByStatus().get(4).status());
        assertEquals(0L, dashboard.roomsByStatus().get(4).count());
        assertEquals("OUT_OF_ORDER", dashboard.roomsByStatus().get(5).status());
        assertEquals(0L, dashboard.roomsByStatus().get(5).count());
        assertEquals(20L, dashboard.totalRooms());
        assertEquals(6L, dashboard.availableRooms());
        assertEquals(HOTEL_TODAY, dashboard.hotelToday());
    }

    /** Verifies every operational block is omitted, and no operational query runs, with no permission granted. */
    @Test
    void shouldOmitAllOperationalBlocksWhenNoOperationalPermissionGranted() {
        DashboardResponse dashboard = dashboardService.getDashboard(noOperationalPermissions());

        assertNull(dashboard.currentlyStaying());
        assertNull(dashboard.arrivalsKpi());
        assertNull(dashboard.departuresKpi());
        assertTrue(dashboard.arrivalRows().isEmpty());
        assertTrue(dashboard.currentlyStayingRows().isEmpty());
        assertTrue(dashboard.departureRows().isEmpty());
        assertTrue(dashboard.recentReservations().isEmpty());
        verifyNoInteractions(frontDeskQueryService);
        verifyNoInteractions(reservationQueryService);
        verify(stayRepository, never()).sumGuestHeadcountInHouseAt(any());
    }

    /** Verifies the Currently Staying KPI sums guest headcount at two instants and reuses the OCCUPIED room count. */
    @Test
    void shouldComputeCurrentlyStayingKpiWhenCheckOutGranted() {
        Instant now = Instant.parse("2026-09-16T05:00:00Z");
        Instant startOfToday = HOTEL_TODAY.atStartOfDay(HOTEL_ZONE).toInstant();
        when(stayRepository.sumGuestHeadcountInHouseAt(now)).thenReturn(12L);
        when(stayRepository.sumGuestHeadcountInHouseAt(startOfToday)).thenReturn(10L);
        when(frontDeskQueryService.inHouse()).thenReturn(List.of());

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(false, true, false, false));

        assertEquals(12L, dashboard.currentlyStaying().guests());
        assertEquals(12L, dashboard.currentlyStaying().occupiedRooms());
        assertEquals(2L, dashboard.currentlyStaying().guestDeltaVsYesterday());
    }

    /** Verifies Currently Staying rows compute nights elapsed from actual check-in to the hotel's current date. */
    @Test
    void shouldComputeNightsElapsedForCurrentlyStayingRows() {
        when(stayRepository.sumGuestHeadcountInHouseAt(any())).thenReturn(0L);
        FrontDeskStayRow row = stayRow(Instant.parse("2026-09-14T03:00:00Z"), LocalDate.of(2026, 9, 20));
        when(frontDeskQueryService.inHouse()).thenReturn(List.of(row));

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(false, true, false, false));

        assertEquals(1, dashboard.currentlyStayingRows().size());
        assertEquals(2L, dashboard.currentlyStayingRows().get(0).nights());
    }

    /** Verifies Today's Departures rows compute nights as the full length of stay, check-in to planned check-out. */
    @Test
    void shouldComputeNightsOfStayForDepartureRows() {
        FrontDeskStayRow row = stayRow(Instant.parse("2026-09-14T03:00:00Z"), LocalDate.of(2026, 9, 16));
        when(frontDeskQueryService.departures(false)).thenReturn(List.of(row));

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(false, true, false, false));

        assertEquals(1, dashboard.departureRows().size());
        assertEquals(2L, dashboard.departureRows().get(0).nights());
    }

    /** Verifies the Arrivals KPI headline is strictly today's check-ins, excluding overdue, with its own attention count. */
    @Test
    void shouldComputeArrivalsKpiStrictlyForTodayExcludingOverdue() {
        FrontDeskArrivalRow readyToday = arrivalRow(HOTEL_TODAY, false);
        FrontDeskArrivalRow attentionToday = arrivalRow(HOTEL_TODAY, true);
        FrontDeskArrivalRow overdueYesterday = arrivalRow(HOTEL_TODAY.minusDays(1), true);
        when(frontDeskQueryService.arrivals()).thenReturn(List.of(readyToday, attentionToday, overdueYesterday));

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(true, false, false, false));

        assertEquals(2L, dashboard.arrivalsKpi().todayCount());
        assertEquals(1L, dashboard.arrivalsKpi().needsAttentionCount());
        assertEquals(3, dashboard.arrivalRows().size());
    }

    /** Verifies the Departures KPI headline is strictly today's planned checkouts, excluding overdue. */
    @Test
    void shouldComputeDeparturesKpiStrictlyForTodayExcludingOverdue() {
        FrontDeskStayRow dueToday = stayRow(Instant.parse("2026-09-10T03:00:00Z"), HOTEL_TODAY);
        FrontDeskStayRow overdueYesterday = stayRow(Instant.parse("2026-09-08T03:00:00Z"), HOTEL_TODAY.minusDays(1));
        when(frontDeskQueryService.departures(true)).thenReturn(List.of(dueToday, overdueYesterday));

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(false, true, true, false));

        assertEquals(1L, dashboard.departuresKpi().todayCount());
        verify(frontDeskQueryService).departures(true);
    }

    /** Verifies the financial-amount permission is passed through to the canonical Front Desk departures query. */
    @Test
    void shouldPassManagePaymentPermissionThroughToDepartures() {
        when(frontDeskQueryService.departures(anyBoolean())).thenReturn(List.of());

        dashboardService.getDashboard(new DashboardPermissions(false, true, false, false));

        verify(frontDeskQueryService).departures(eq(false));
    }

    /** Verifies the operational table row limit bounds presentation without affecting the KPI count. */
    @Test
    void shouldLimitArrivalRowsWithoutAffectingTheKpiCount() {
        List<FrontDeskArrivalRow> sevenTodayArrivals = List.of(
                arrivalRow(HOTEL_TODAY, false), arrivalRow(HOTEL_TODAY, false), arrivalRow(HOTEL_TODAY, false),
                arrivalRow(HOTEL_TODAY, false), arrivalRow(HOTEL_TODAY, false), arrivalRow(HOTEL_TODAY, false),
                arrivalRow(HOTEL_TODAY, false));
        when(frontDeskQueryService.arrivals()).thenReturn(sevenTodayArrivals);

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(true, false, false, false));

        assertEquals(7L, dashboard.arrivalsKpi().todayCount());
        assertEquals(5, dashboard.arrivalRows().size());
    }

    /** Verifies Recent Reservations is populated with VIEW_BOOKING, reusing the existing read model as-is. */
    @Test
    void shouldReturnRecentReservationsWithViewBookingPermission() {
        ReservationSummaryResponse recent = new ReservationSummaryResponse(
                UUID.randomUUID(), "RSV-0001", "Jane Doe", "101", "CONFIRMED", BookingSource.DIRECT, null,
                LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22), null, "VND");
        when(reservationQueryService.findRecent()).thenReturn(List.of(recent));

        DashboardResponse dashboard = dashboardService.getDashboard(
                new DashboardPermissions(false, false, false, true));

        assertEquals(List.of(recent), dashboard.recentReservations());
    }

    /** Verifies Recent Reservations is omitted, and its query never runs, without VIEW_BOOKING permission. */
    @Test
    void shouldOmitRecentReservationsWithoutViewBookingPermission() {
        DashboardResponse dashboard = dashboardService.getDashboard(noOperationalPermissions());

        assertTrue(dashboard.recentReservations().isEmpty());
        verify(reservationQueryService, never()).findRecent();
    }

    private DashboardPermissions noOperationalPermissions() {
        return new DashboardPermissions(false, false, false, false);
    }

    private Object[] row(RoomStatus status, long count) {
        return new Object[] {status, count};
    }

    private FrontDeskArrivalRow arrivalRow(LocalDate checkInDate, boolean needsAttention) {
        ArrivalReadiness readiness = new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of());
        return new FrontDeskArrivalRow(
                UUID.randomUUID(), "RSV-ARR", "Guest", "G-1", BookingSource.DIRECT, null, checkInDate,
                checkInDate.isBefore(HOTEL_TODAY), needsAttention, readiness, List.of(), false, null);
    }

    private FrontDeskStayRow stayRow(Instant actualCheckInAt, LocalDate plannedCheckOutDate) {
        return new FrontDeskStayRow(
                UUID.randomUUID(), "RSV-STAY", "Guest", "G-1", List.of(), actualCheckInAt, plannedCheckOutDate,
                false, 0L, false, false, null, "VND", BookingSource.DIRECT, 0L);
    }
}
