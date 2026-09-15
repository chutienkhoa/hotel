package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.dto.common.response.DashboardMonthCountResponse;
import com.example.hotel.dto.common.response.DashboardRoomTypeCountResponse;
import com.example.hotel.dto.common.response.DashboardSourceCountResponse;
import com.example.hotel.dto.common.response.DashboardStatusCountResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import com.example.hotel.repository.room.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the read-only aggregation of approved Dashboard v1 and v2 metrics. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private StayRepository stayRepository;

    @Mock
    private ExpenseRepository expenseRepository;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-06-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        dashboardService = new DashboardService(
                reservationRepository, roomRepository, stayRepository, expenseRepository, clock);
    }

    /** Verifies all approved summary, status, and operational-alert metrics. */
    @Test
    void shouldReturnOnlyApprovedDashboardMetrics() {
        when(reservationRepository.countAllByStatus()).thenReturn(List.of(
                row(ReservationStatus.DRAFT, 2L), row(ReservationStatus.CONFIRMED, 10L)));
        when(roomRepository.countByActiveTrue()).thenReturn(5L);
        when(roomRepository.countActiveByStatus()).thenReturn(List.of(
                row(RoomStatus.AVAILABLE, 2L),
                row(RoomStatus.DIRTY, 1L),
                row(RoomStatus.MAINTENANCE, 2L)));
        when(stayRepository.countByStatus(StayStatus.CHECKED_IN)).thenReturn(3L);
        when(stayRepository.countCheckedInScheduledForCheckOutOn(
                        StayStatus.CHECKED_IN, LocalDate.of(2026, 6, 15)))
                .thenReturn(2L);
        when(expenseRepository.countAllByStatus()).thenReturn(List.of(
                row(ExpenseStatus.DRAFT, 4L), row(ExpenseStatus.POSTED, 1L)));
        when(reservationRepository.countByCheckInMonthWithin(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.<Object[]>of(monthRow(2026, 1, 2L), monthRow(2026, 6, 3L)));
        when(reservationRepository.countBookedRoomsByRoomTypeWithin(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.<Object[]>of(roomTypeRow("DOUBLE", "Double Room", 3L)));
        when(reservationRepository.countBySourceWithin(
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)))
                .thenReturn(List.<Object[]>of(sourceRow(BookingSource.DIRECT, 4L)));

        DashboardResponse dashboard = dashboardService.getDashboard();

        assertEquals(5L, dashboard.reservationsThisYear());
        assertEquals(3L, dashboard.reservationsThisMonth());
        assertEquals("JUN 2026", dashboard.currentMonthLabel());
        assertEquals(5L, dashboard.activeRooms());
        assertEquals(2L, dashboard.availableRooms());
        assertEquals(3L, dashboard.checkedInStays());
        assertEquals(2L, dashboard.checkOutTodayStays());
        assertEquals(
                List.of(
                        new DashboardStatusCountResponse("DRAFT", 2L),
                        new DashboardStatusCountResponse("CONFIRMED", 10L)),
                dashboard.reservationsByStatus());
        assertEquals(
                List.of(
                        new DashboardStatusCountResponse("AVAILABLE", 2L),
                        new DashboardStatusCountResponse("DIRTY", 1L),
                        new DashboardStatusCountResponse("MAINTENANCE", 2L)),
                dashboard.roomsByStatus());
        assertEquals(
                List.of(
                        new DashboardStatusCountResponse("DIRTY", 1L),
                        new DashboardStatusCountResponse("CLEANING", 0L),
                        new DashboardStatusCountResponse("MAINTENANCE", 2L),
                        new DashboardStatusCountResponse("OUT_OF_ORDER", 0L)),
                dashboard.operationalRoomAlerts());
        assertEquals(
                List.of(
                        new DashboardStatusCountResponse("DRAFT", 4L),
                        new DashboardStatusCountResponse("POSTED", 1L)),
                dashboard.expensesByStatus());
        assertEquals(12, dashboard.reservationsByCheckInMonth().size());
        assertEquals(
                new DashboardMonthCountResponse(2026, 1, 2L),
                dashboard.reservationsByCheckInMonth().getFirst());
        assertEquals(
                new DashboardMonthCountResponse(2026, 2, 0L),
                dashboard.reservationsByCheckInMonth().get(1));
        assertEquals(
                new DashboardMonthCountResponse(2026, 6, 3L),
                dashboard.reservationsByCheckInMonth().get(5));
        assertEquals(
                new DashboardMonthCountResponse(2026, 12, 0L),
                dashboard.reservationsByCheckInMonth().getLast());
        assertEquals(
                List.of(new DashboardRoomTypeCountResponse("DOUBLE", "Double Room", 3L)),
                dashboard.bookedRoomsByRoomType());
        assertEquals(
                List.of(
                        new DashboardSourceCountResponse("DIRECT", 4L),
                        new DashboardSourceCountResponse("AGODA", 0L),
                        new DashboardSourceCountResponse("BOOKING_COM", 0L),
                        new DashboardSourceCountResponse("AIRBNB", 0L)),
                dashboard.reservationsBySource());
        verify(stayRepository).countByStatus(StayStatus.CHECKED_IN);
    }

    /** Verifies the reporting-year bounds derive from the injected business-time clock. */
    @Test
    void shouldUseAsiaHoChiMinhClockForCurrentCalendarYearBounds() {
        Clock boundaryClock = Clock.fixed(
                Instant.parse("2025-12-31T17:30:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        dashboardService = new DashboardService(
                reservationRepository, roomRepository, stayRepository, expenseRepository, boundaryClock);
        stubEmptyDashboardMetrics();
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDateExclusive = LocalDate.of(2027, 1, 1);
        when(reservationRepository.countByCheckInMonthWithin(startDate, endDateExclusive))
                .thenReturn(List.<Object[]>of());
        when(reservationRepository.countBookedRoomsByRoomTypeWithin(startDate, endDateExclusive))
                .thenReturn(List.<Object[]>of());
        when(reservationRepository.countBySourceWithin(startDate, endDateExclusive))
                .thenReturn(List.<Object[]>of());

        dashboardService.getDashboard();

        verify(reservationRepository).countByCheckInMonthWithin(startDate, endDateExclusive);
        verify(reservationRepository).countBookedRoomsByRoomTypeWithin(startDate, endDateExclusive);
        verify(reservationRepository).countBySourceWithin(startDate, endDateExclusive);
    }

    /** Verifies the September KPI uses the same zero-filled monthly check-in dataset as the chart. */
    @Test
    void shouldDeriveYearAndSeptemberKpisFromCheckInMonthCounts() {
        Clock septemberClock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        dashboardService = new DashboardService(
                reservationRepository, roomRepository, stayRepository, expenseRepository, septemberClock);
        stubEmptyDashboardMetrics();
        LocalDate yearStart = LocalDate.of(2026, 1, 1);
        LocalDate nextYearStart = LocalDate.of(2027, 1, 1);
        when(reservationRepository.countByCheckInMonthWithin(yearStart, nextYearStart)).thenReturn(List.<Object[]>of(
                monthRow(2026, 1, 10L), monthRow(2026, 2, 5L), monthRow(2026, 9, 8L), monthRow(2026, 12, 4L)));
        when(reservationRepository.countBookedRoomsByRoomTypeWithin(yearStart, nextYearStart)).thenReturn(List.of());
        when(reservationRepository.countBySourceWithin(yearStart, nextYearStart)).thenReturn(List.of());

        DashboardResponse dashboard = dashboardService.getDashboard();

        assertEquals(27L, dashboard.reservationsThisYear());
        assertEquals(8L, dashboard.reservationsThisMonth());
        assertEquals("SEP 2026", dashboard.currentMonthLabel());
        assertEquals(0L, dashboard.reservationsByCheckInMonth().get(2).count());
    }

    /** Verifies the Dashboard service has no dependency on financial domain components. */
    @Test
    void shouldNotDependOnFinancialDomainComponents() {
        assertFalse(List.of(DashboardService.class.getDeclaredFields()).stream()
                .map(field -> field.getType().getSimpleName())
                .anyMatch(name -> name.contains("Charge")
                        || name.contains("Payment")
                        || name.contains("Balance")));
    }

    /** Creates one repository grouped-count result row. */
    private Object[] row(Enum<?> status, long count) {
        return new Object[] {status, count};
    }

    /** Stubs the existing Dashboard v1 metrics with empty values. */
    private void stubEmptyDashboardMetrics() {
        when(reservationRepository.countAllByStatus()).thenReturn(List.of());
        when(roomRepository.countByActiveTrue()).thenReturn(0L);
        when(roomRepository.countActiveByStatus()).thenReturn(List.of());
        when(stayRepository.countByStatus(StayStatus.CHECKED_IN)).thenReturn(0L);
        when(stayRepository.countCheckedInScheduledForCheckOutOn(org.mockito.ArgumentMatchers.eq(StayStatus.CHECKED_IN), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        when(expenseRepository.countAllByStatus()).thenReturn(List.of());
    }

    /** Creates one year-month aggregate query row. */
    private Object[] monthRow(int year, int month, long count) {
        return new Object[] {year, month, count};
    }

    /** Creates one RoomType aggregate query row. */
    private Object[] roomTypeRow(String code, String name, long count) {
        return new Object[] {code, name, count};
    }

    /** Creates one booking-source aggregate query row. */
    private Object[] sourceRow(BookingSource source, long count) {
        return new Object[] {source, count};
    }
}
