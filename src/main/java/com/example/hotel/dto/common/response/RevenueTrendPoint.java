package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One point of the six-month Revenue Trend: the Task 29 Total Revenue (Room Revenue plus Additional Revenue) of a month.
 *
 * @param month calendar month
 * @param totalRevenue Total Revenue of that month in the report currency
 */
public record RevenueTrendPoint(YearMonth month, BigDecimal totalRevenue) {}
