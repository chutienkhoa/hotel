package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Locale-free, immutable result of the Monthly Financial Report. It carries no display text so the web
 * page, and later PDF and Excel exports, can all present the same calculated result.
 *
 * @param month the reported calendar month
 * @param monthStart first day of the month (inclusive)
 * @param nextMonthStart first day of the following month (exclusive)
 * @param currency report/base currency code, always {@code VND}
 * @param roomRevenue recognized VND Room Revenue allocated by booked calendar room-night
 * @param additionalRevenue recognized Additional Revenue ({@code RECORDED}, by revenue date)
 * @param totalRevenue room plus additional revenue
 * @param expense recognized Expense ({@code POSTED}, by expense date)
 * @param netProfit total revenue minus expense
 * @param profitMargin net profit / total revenue x 100 (2 decimals, half-up), or {@code null} when total
 *     revenue is zero
 * @param nonVndWarning excluded non-VND Room Revenue metadata, or {@code null} when none was excluded
 */
public record MonthlyFinancialReport(
        YearMonth month,
        LocalDate monthStart,
        LocalDate nextMonthStart,
        String currency,
        BigDecimal roomRevenue,
        BigDecimal additionalRevenue,
        BigDecimal totalRevenue,
        BigDecimal expense,
        BigDecimal netProfit,
        BigDecimal profitMargin,
        NonVndRoomRevenueWarning nonVndWarning) {}
