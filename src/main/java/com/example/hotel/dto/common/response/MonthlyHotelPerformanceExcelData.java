package com.example.hotel.dto.common.response;

import java.util.List;

/**
 * Business dataset of the Monthly Hotel Performance Excel export: the shared summary plus the row-level detail
 * sheets that only Excel needs. The Occupancy sheet needs no rows of its own because
 * {@code report.occupancy().roomTypePerformance()} already carries the full RoomType list.
 *
 * @param report shared Monthly Hotel Performance summary (also used by the PDF)
 * @param reservations Reservations checking in during the month, all statuses, one row each
 * @param payments PAID and REFUNDED Payments paid during the month
 * @param expenses POSTED Expenses dated in the month
 */
public record MonthlyHotelPerformanceExcelData(
        MonthlyHotelPerformanceReport report,
        List<MonthlyReservationExportRow> reservations,
        List<MonthlyPaymentExportRow> payments,
        List<MonthlyExpenseExportRow> expenses) {}
