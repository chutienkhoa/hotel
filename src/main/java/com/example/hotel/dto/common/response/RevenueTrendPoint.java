package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One point of the six-month Revenue Trend, taken from the Task 29 result of that month. The PDF charts only
 * {@code totalRevenue}; the Excel workbook charts both series.
 *
 * @param month calendar month
 * @param totalRevenue Total Revenue (Room Revenue plus Additional Revenue) of that month in the report currency
 * @param roomRevenue Room Revenue of that month in the report currency
 */
public record RevenueTrendPoint(YearMonth month, BigDecimal totalRevenue, BigDecimal roomRevenue) {}
