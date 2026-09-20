package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.NonVndRoomRevenueWarning;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.common.AdditionalRevenueCategoryTotalRow;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the shared dataset reuses Tasks 29/30 unchanged and adds only the source, trend, category and comparison data. */
class MonthlyHotelPerformanceReportServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final MonthlyFinancialReportService financialService = mock(MonthlyFinancialReportService.class);
    private final MonthlyOccupancyReportService occupancyService = mock(MonthlyOccupancyReportService.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final AdditionalRevenueRepository additionalRevenues = mock(AdditionalRevenueRepository.class);
    private final Map<YearMonth, MonthlyFinancialReport> financials = new HashMap<>();
    private final Map<YearMonth, MonthlyOccupancyReport> occupancies = new HashMap<>();

    private final MonthlyHotelPerformanceReportService service = createService();

    private MonthlyHotelPerformanceReportService createService() {
        when(financialService.report(any())).thenAnswer(invocation -> {
            YearMonth month = invocation.getArgument(0);
            return financials.computeIfAbsent(month, key -> financial(key, "0", "0", "0"));
        });
        when(occupancyService.report(any())).thenAnswer(invocation -> {
            YearMonth month = invocation.getArgument(0);
            return occupancies.computeIfAbsent(month, key -> occupancy(key, "50.00"));
        });
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        return new MonthlyHotelPerformanceReportService(financialService, occupancyService, reservations, additionalRevenues, clock);
    }

    private MonthlyHotelPerformanceReportService service() {
        return service;
    }

    private static MonthlyFinancialReport financial(YearMonth month, String room, String additional, String expense) {
        BigDecimal total = new BigDecimal(room).add(new BigDecimal(additional));
        return new MonthlyFinancialReport(month, month.atDay(1), month.plusMonths(1).atDay(1), "VND", new BigDecimal(room),
                new BigDecimal(additional), total, new BigDecimal(expense), total.subtract(new BigDecimal(expense)), null, null);
    }

    private static MonthlyOccupancyReport occupancy(YearMonth month, String rate) {
        return new MonthlyOccupancyReport(month, month.atDay(1), month.plusMonths(1).atDay(1), month.atDay(1),
                month.plusMonths(1).atDay(1), month.atEndOfMonth(), 10, 20, rate == null ? null : new BigDecimal(rate), List.of());
    }

    /** Confirms the Task 29 and Task 30 results are composed unchanged, not recalculated or copied. */
    @Test
    void shouldReuseFinancialAndOccupancyResultsAsIs() {
        MonthlyFinancialReport financial = financial(SEPTEMBER, "1180000", "104000", "356000");
        MonthlyOccupancyReport occupancy = occupancy(SEPTEMBER, "78.40");
        financials.put(SEPTEMBER, financial);
        occupancies.put(SEPTEMBER, occupancy);

        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertSame(financial, report.financial());
        assertSame(occupancy, report.occupancy());
        assertEquals(SEPTEMBER, report.month());
        assertEquals(LocalDate.of(2026, 9, 30), report.generatedOn());
    }

    /** Confirms the trend has six calendar months, oldest first, ending at the selected month, one call per month. */
    @Test
    void shouldBuildSixMonthTrendEndingAtSelectedMonth() {
        for (int back = 0; back < 6; back++) {
            YearMonth month = SEPTEMBER.minusMonths(back);
            financials.put(month, financial(month, String.valueOf(1000 * (6 - back)), "0", "0"));
        }

        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertEquals(6, report.revenueTrend().size());
        assertEquals(YearMonth.of(2026, 4), report.revenueTrend().get(0).month());
        assertEquals(SEPTEMBER, report.revenueTrend().get(5).month());
        assertEquals(new BigDecimal("6000"), report.revenueTrend().get(5).totalRevenue());
        assertEquals(new BigDecimal("1000"), report.revenueTrend().get(0).totalRevenue());
        for (int back = 0; back < 6; back++) {
            verify(financialService, times(1)).report(SEPTEMBER.minusMonths(back));
        }
    }

    /** Confirms all four sources are present in enum order, zero-filled, with 2-decimal percentages. */
    @Test
    void shouldZeroFillSourcesAndComputePercentages() {
        when(reservations.countBySourceWithin(any(), any())).thenReturn(List.of(
                new Object[] {BookingSource.AGODA, 1L}, new Object[] {BookingSource.AIRBNB, 2L}));

        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertEquals(3, report.reservationCount());
        List<ReservationSourceShare> sources = report.reservationSources();
        assertEquals(List.of(BookingSource.DIRECT, BookingSource.AGODA, BookingSource.BOOKING_COM, BookingSource.AIRBNB),
                sources.stream().map(ReservationSourceShare::source).toList());
        assertEquals(0, sources.get(0).count());
        assertEquals(new BigDecimal("0.00"), sources.get(0).percentage());
        assertEquals(new BigDecimal("33.33"), sources.get(1).percentage());
        assertEquals(0, sources.get(2).count());
        assertEquals(new BigDecimal("66.67"), sources.get(3).percentage());
        verify(reservations).countBySourceWithin(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
    }

    /** Confirms a month without reservations gives zero counts and 0.00 percentages, never a division error. */
    @Test
    void shouldHandleZeroReservations() {
        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertEquals(0, report.reservationCount());
        report.reservationSources().forEach(share -> {
            assertEquals(0, share.count());
            assertEquals(new BigDecimal("0.00"), share.percentage());
        });
    }

    /** Confirms categories keep the repository order, use RECORDED and share the month's Additional Revenue. */
    @Test
    void shouldBuildCategoryShares() {
        financials.put(SEPTEMBER, financial(SEPTEMBER, "1000", "200", "0"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(additionalRevenues.sumAmountByCategoryWithin(any(), any(), any())).thenReturn(List.of(
                new AdditionalRevenueCategoryTotalRow(first, "A", "Alpha", new BigDecimal("150")),
                new AdditionalRevenueCategoryTotalRow(second, "B", "Beta", new BigDecimal("50"))));

        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        List<AdditionalRevenueCategoryShare> categories = report.additionalRevenueByCategory();
        assertEquals(2, categories.size());
        assertEquals("Alpha", categories.get(0).categoryName());
        assertEquals(new BigDecimal("75.00"), categories.get(0).percentage());
        assertEquals(new BigDecimal("25.00"), categories.get(1).percentage());
        verify(additionalRevenues).sumAmountByCategoryWithin(
                AdditionalRevenueStatus.RECORDED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
    }

    /** Confirms zero Additional Revenue gives no categories, and zero totals give 0.00 percentages. */
    @Test
    void shouldHandleZeroAdditionalRevenue() {
        when(additionalRevenues.sumAmountByCategoryWithin(any(), any(), any())).thenReturn(List.of(
                new AdditionalRevenueCategoryTotalRow(UUID.randomUUID(), "A", "Alpha", BigDecimal.ZERO)));

        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertEquals(new BigDecimal("0.00"), report.additionalRevenueByCategory().get(0).percentage());
    }

    /** Confirms the non-VND warning is carried through inside the reused financial result. */
    @Test
    void shouldPreserveNonVndWarning() {
        NonVndRoomRevenueWarning warning = new NonVndRoomRevenueWarning(1, 2, List.of("USD"));
        financials.put(SEPTEMBER, new MonthlyFinancialReport(SEPTEMBER, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1),
                "VND", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, warning));

        assertSame(warning, service().build(SEPTEMBER).financial().nonVndWarning());
    }

    /** Confirms a supported month with no activity yields zero values and unavailable comparisons. */
    @Test
    void shouldBuildZeroDataMonth() {
        MonthlyHotelPerformanceReport report = service().build(SEPTEMBER);

        assertEquals(0, report.financial().totalRevenue().signum());
        assertEquals(6, report.revenueTrend().size());
        assertEquals(0, report.additionalRevenueByCategory().size());
        assertNull(report.previousMonthComparison().totalRevenueChangePercent());
        assertNull(report.previousMonthComparison().expenseChangePercent());
        assertNull(report.previousMonthComparison().netProfitChangePercent());
    }

    // ----- comparisons ---------------------------------------------------------------------------------

    private MonthlyHotelPerformanceReport withFinancials(MonthlyFinancialReport current, MonthlyFinancialReport previous) {
        financials.put(SEPTEMBER, current);
        financials.put(SEPTEMBER.minusMonths(1), previous);
        return service().build(SEPTEMBER);
    }

    /** Confirms revenue increase, decrease and no change use (current - previous) / abs(previous) x 100. */
    @Test
    void shouldCompareRevenueIncreaseDecreaseAndSame() {
        assertEquals(new BigDecimal("50.0000"),
                withFinancials(financial(SEPTEMBER, "150", "0", "0"), financial(SEPTEMBER.minusMonths(1), "100", "0", "0"))
                        .previousMonthComparison().totalRevenueChangePercent());
        assertEquals(new BigDecimal("-25.0000"),
                withFinancials(financial(SEPTEMBER, "75", "0", "0"), financial(SEPTEMBER.minusMonths(1), "100", "0", "0"))
                        .previousMonthComparison().totalRevenueChangePercent());
        assertEquals(new BigDecimal("0.0000"),
                withFinancials(financial(SEPTEMBER, "100", "0", "0"), financial(SEPTEMBER.minusMonths(1), "100", "0", "0"))
                        .previousMonthComparison().totalRevenueChangePercent());
    }

    /** Confirms expense and net profit are compared with the same relative formula. */
    @Test
    void shouldCompareExpenseAndNetProfit() {
        MonthlyHotelPerformanceReport report = withFinancials(
                financial(SEPTEMBER, "200", "0", "120"), financial(SEPTEMBER.minusMonths(1), "100", "0", "50"));

        assertEquals(new BigDecimal("140.0000"), report.previousMonthComparison().expenseChangePercent());
        assertEquals(new BigDecimal("60.0000"), report.previousMonthComparison().netProfitChangePercent());
    }

    /** Confirms a previous value of zero gives N/A (null) rather than infinity, for every money KPI. */
    @Test
    void shouldReturnNullWhenPreviousValueIsZero() {
        MonthlyHotelPerformanceReport report = withFinancials(
                financial(SEPTEMBER, "100", "0", "10"), financial(SEPTEMBER.minusMonths(1), "0", "0", "0"));

        assertNull(report.previousMonthComparison().totalRevenueChangePercent());
        assertNull(report.previousMonthComparison().expenseChangePercent());
        assertNull(report.previousMonthComparison().netProfitChangePercent());
    }

    /** Confirms a negative previous Net Profit uses its absolute value, so a recovery shows as an increase. */
    @Test
    void shouldUseAbsolutePreviousValueForNegativeNetProfit() {
        MonthlyHotelPerformanceReport report = withFinancials(
                financial(SEPTEMBER, "150", "0", "100"), financial(SEPTEMBER.minusMonths(1), "0", "0", "100"));

        assertEquals(new BigDecimal("150.0000"), report.previousMonthComparison().netProfitChangePercent());
    }

    /** Confirms occupancy is compared in percentage points, not relatively. */
    @Test
    void shouldCompareOccupancyInPercentagePoints() {
        occupancies.put(SEPTEMBER, occupancy(SEPTEMBER, "78.40"));
        occupancies.put(SEPTEMBER.minusMonths(1), occupancy(SEPTEMBER.minusMonths(1), "73.20"));
        assertEquals(new BigDecimal("5.20"), service().build(SEPTEMBER).previousMonthComparison().occupancyPointDifference());

        occupancies.put(SEPTEMBER, occupancy(SEPTEMBER, "70.00"));
        assertEquals(new BigDecimal("-3.20"), service().build(SEPTEMBER).previousMonthComparison().occupancyPointDifference());

        occupancies.put(SEPTEMBER, occupancy(SEPTEMBER, "73.20"));
        assertEquals(0, service().build(SEPTEMBER).previousMonthComparison().occupancyPointDifference().signum());
    }

    /** Confirms an unavailable occupancy rate (either month, e.g. zero sellable nights) gives N/A. */
    @Test
    void shouldReturnNullOccupancyDifferenceWhenARateIsUnavailable() {
        occupancies.put(SEPTEMBER, occupancy(SEPTEMBER, "78.40"));
        occupancies.put(SEPTEMBER.minusMonths(1), occupancy(SEPTEMBER.minusMonths(1), null));
        assertNull(service().build(SEPTEMBER).previousMonthComparison().occupancyPointDifference());

        occupancies.put(SEPTEMBER, occupancy(SEPTEMBER, null));
        occupancies.put(SEPTEMBER.minusMonths(1), occupancy(SEPTEMBER.minusMonths(1), "60.00"));
        assertNull(service().build(SEPTEMBER).previousMonthComparison().occupancyPointDifference());
    }

    // ----- month rules ---------------------------------------------------------------------------------

    /** Confirms an unsupported previous month only makes the occupancy comparison N/A; the PDF data still builds. */
    @Test
    void shouldStillBuildWhenPreviousMonthOccupancyIsUnsupported() {
        when(occupancyService.report(SEPTEMBER.minusMonths(1))).thenThrow(new ReportPeriodUnavailableException(
                Reason.HISTORY_UNAVAILABLE, SEPTEMBER, "before history"));

        MonthlyHotelPerformanceReport report = service.build(SEPTEMBER);

        assertNull(report.previousMonthComparison().occupancyPointDifference());
        assertEquals(6, report.revenueTrend().size());
    }

    /** Confirms a future or unsupported selected month is rejected before any financial data is read. */
    @Test
    void shouldRejectSelectedMonthWithoutOccupancySupport() {
        when(occupancyService.report(SEPTEMBER)).thenThrow(new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "future"));

        ReportPeriodUnavailableException exception =
                assertThrows(ReportPeriodUnavailableException.class, () -> service.build(SEPTEMBER));

        assertEquals(Reason.FUTURE_MONTH, exception.getReason());
        verifyNoInteractions(financialService);
    }

    /** Confirms an integrity failure in the previous month is not hidden as an unavailable comparison. */
    @Test
    void shouldPropagateIntegrityFailureOfPreviousMonth() {
        when(occupancyService.report(SEPTEMBER.minusMonths(1))).thenThrow(new ReportDataIntegrityException("corrupt"));

        assertThrows(ReportDataIntegrityException.class, () -> service.build(SEPTEMBER));
    }
}
