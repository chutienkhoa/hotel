package com.example.hotel.dto.common.response;

import java.math.BigDecimal;

/**
 * Comparison of the selected month with the previous calendar month. Money KPIs use the relative change
 * {@code (current - previous) / abs(previous) x 100}; Occupancy uses the difference in percentage points.
 * Every value is {@code null} when the comparison is unavailable (previous value 0 or unknown).
 *
 * @param totalRevenueChangePercent relative Total Revenue change, scale 4 HALF_UP
 * @param expenseChangePercent relative Total Expenses change, scale 4 HALF_UP
 * @param netProfitChangePercent relative Net Profit change, scale 4 HALF_UP
 * @param occupancyPointDifference current minus previous occupancy rate in percentage points
 */
public record MonthlyPerformanceComparison(
        BigDecimal totalRevenueChangePercent,
        BigDecimal expenseChangePercent,
        BigDecimal netProfitChangePercent,
        BigDecimal occupancyPointDifference) {}
