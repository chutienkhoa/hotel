package com.example.hotel.dto.common.response;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Locale-free, layout-free business dataset of the Monthly Hotel Performance Report. It composes the
 * unchanged Task 29 and Task 30 results and adds only the datasets they do not provide, so the PDF (and
 * later the Excel export) present exactly the calculated results without recalculating anything.
 *
 * @param month selected calendar month
 * @param generatedOn hotel-zone date the dataset was built
 * @param financial Task 29 result for the month
 * @param occupancy Task 30 result for the month (completed nights only in the current month)
 * @param previousMonthComparison comparison with the previous calendar month
 * @param revenueTrend six months ending at {@code month}, oldest first, Total Revenue per month
 * @param reservationCount total Reservations counted for the source breakdown
 * @param reservationSources all four booking sources in enum order, zero-filled
 * @param additionalRevenueByCategory every category with recorded revenue, ordered by amount then code
 */
public record MonthlyHotelPerformanceReport(
        YearMonth month,
        LocalDate generatedOn,
        MonthlyFinancialReport financial,
        MonthlyOccupancyReport occupancy,
        MonthlyPerformanceComparison previousMonthComparison,
        List<RevenueTrendPoint> revenueTrend,
        long reservationCount,
        List<ReservationSourceShare> reservationSources,
        List<AdditionalRevenueCategoryShare> additionalRevenueByCategory) {}
