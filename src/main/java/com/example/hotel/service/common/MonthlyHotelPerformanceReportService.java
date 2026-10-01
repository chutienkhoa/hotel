package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.MonthlyPerformanceComparison;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.dto.common.response.RevenueTrendPoint;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.common.AdditionalRevenueCategoryTotalRow;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Monthly Hotel Performance dataset by composing the unchanged Task 29 financial and Task 30
 * occupancy services and adding only the source breakdown, six-month trend, previous-month comparison and
 * category totals. It contains no presentation and no PDF concerns.
 */
@Service
public class MonthlyHotelPerformanceReportService {

    private static final int TREND_MONTHS = 6;
    private static final int PERCENT_SCALE = 2;
    private static final int CHANGE_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MonthlyFinancialReportService financialService;
    private final MonthlyOccupancyReportService occupancyService;
    private final ReservationRepository reservations;
    private final AdditionalRevenueRepository additionalRevenues;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param financialService Task 29 financial report service
     * @param occupancyService Task 30 occupancy report service
     * @param reservations source of Reservation counts by booking source
     * @param additionalRevenues source of Additional Revenue totals per category
     * @param clock hotel business clock
     */
    public MonthlyHotelPerformanceReportService(
            MonthlyFinancialReportService financialService,
            MonthlyOccupancyReportService occupancyService,
            ReservationRepository reservations,
            AdditionalRevenueRepository additionalRevenues,
            Clock clock) {
        this.financialService = financialService;
        this.occupancyService = occupancyService;
        this.reservations = reservations;
        this.additionalRevenues = additionalRevenues;
        this.clock = clock;
    }

    /**
     * Builds the dataset for one calendar month. The selected month must be reportable for occupancy
     * (not in the future and inside supported inventory history); the previous month may lack occupancy
     * history, in which case only the occupancy comparison is unavailable.
     *
     * @param month selected month
     * @return the immutable dataset
     * @throws ReportPeriodUnavailableException if the selected month is in the future or before supported history
     * @throws com.example.hotel.exception.ReportDataIntegrityException if used data is inconsistent
     */
    @Transactional(readOnly = true)
    public MonthlyHotelPerformanceReport build(YearMonth month) {
        MonthlyOccupancyReport occupancy = occupancyService.report(month);
        MonthlyFinancialReport financial = financialService.report(month);
        YearMonth previousMonth = month.minusMonths(1);
        MonthlyFinancialReport previousFinancial = financialService.report(previousMonth);
        MonthlyOccupancyReport previousOccupancy = previousOccupancy(previousMonth);

        List<RevenueTrendPoint> trend = new ArrayList<>();
        for (int back = TREND_MONTHS - 1; back >= 0; back--) {
            YearMonth trendMonth = month.minusMonths(back);
            MonthlyFinancialReport source = back == 0
                    ? financial
                    : back == 1 ? previousFinancial : financialService.report(trendMonth);
            trend.add(new RevenueTrendPoint(trendMonth, source.totalRevenue(), source.roomRevenue()));
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);
        Map<BookingSource, Long> counts = new EnumMap<>(BookingSource.class);
        for (Object[] row : reservations.countBySourceWithin(monthStart, nextMonthStart)) {
            counts.put((BookingSource) row[0], (Long) row[1]);
        }
        long reservationCount = counts.values().stream().mapToLong(Long::longValue).sum();
        List<ReservationSourceShare> sources = new ArrayList<>();
        for (BookingSource source : BookingSource.values()) {
            long count = counts.getOrDefault(source, 0L);
            sources.add(new ReservationSourceShare(source, count, percentage(BigDecimal.valueOf(count), BigDecimal.valueOf(reservationCount))));
        }

        List<AdditionalRevenueCategoryShare> categories = new ArrayList<>();
        for (AdditionalRevenueCategoryTotalRow row :
                additionalRevenues.sumAmountByCategoryWithin(AdditionalRevenueStatus.RECORDED, monthStart, nextMonthStart)) {
            categories.add(new AdditionalRevenueCategoryShare(
                    row.categoryId(), row.categoryCode(), row.categoryName(), row.totalAmount(),
                    percentage(row.totalAmount(), financial.additionalRevenue())));
        }

        MonthlyPerformanceComparison comparison = new MonthlyPerformanceComparison(
                relativeChange(financial.totalRevenue(), previousFinancial.totalRevenue()),
                relativeChange(financial.expense(), previousFinancial.expense()),
                relativeChange(financial.netProfit(), previousFinancial.netProfit()),
                pointDifference(occupancy, previousOccupancy));

        return new MonthlyHotelPerformanceReport(
                month, LocalDate.now(clock), financial, occupancy, comparison, List.copyOf(trend), reservationCount,
                List.copyOf(sources), List.copyOf(categories));
    }

    private MonthlyOccupancyReport previousOccupancy(YearMonth previousMonth) {
        try {
            return occupancyService.report(previousMonth);
        } catch (ReportPeriodUnavailableException exception) {
            return null;
        }
    }

    private BigDecimal relativeChange(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(HUNDRED)
                .divide(previous.abs(), CHANGE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal pointDifference(MonthlyOccupancyReport current, MonthlyOccupancyReport previous) {
        if (previous == null || current.occupancyRate() == null || previous.occupancyRate() == null) {
            return null;
        }
        return current.occupancyRate().subtract(previous.occupancyRate());
    }

    private BigDecimal percentage(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE);
        }
        return part.multiply(HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
