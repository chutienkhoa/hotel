package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Locale-free result of the Monthly Occupancy Report (spec §61). Occupied room-nights come from actual
 * StayRoomAssignment history; "available" here always means SELLABLE room-nights (Room inventory history
 * without MAINTENANCE / OUT_OF_ORDER), not rooms whose current status is AVAILABLE. Hotel occupancy is
 * calculated from the total nights, never by averaging RoomType rates.
 *
 * @param month requested calendar month
 * @param monthStart first day of the month
 * @param nextMonthStart first day of the following month (exclusive month end)
 * @param reportStart first hotel night included
 * @param reportEnd exclusive end: the next month start for a completed month, the hotel date for the current month
 * @param reportedThrough last hotel night included, or {@code null} when no night has completed yet
 * @param occupiedRoomNights total occupied room-nights
 * @param sellableRoomNights total sellable room-nights
 * @param occupancyRate occupied / sellable x 100 with 2 decimals (HALF_UP), or {@code null} when sellable is 0
 * @param roomTypePerformance one row per historical RoomType, ordered by RoomType code
 */
public record MonthlyOccupancyReport(
        YearMonth month,
        LocalDate monthStart,
        LocalDate nextMonthStart,
        LocalDate reportStart,
        LocalDate reportEnd,
        LocalDate reportedThrough,
        long occupiedRoomNights,
        long sellableRoomNights,
        BigDecimal occupancyRate,
        List<RoomTypeOccupancy> roomTypePerformance) {

    /**
     * Tells whether the report stops before the end of the month (the current month, completed nights only).
     *
     * @return {@code true} when {@code reportEnd} is before the next month start
     */
    public boolean partialMonth() {
        return reportEnd.isBefore(nextMonthStart);
    }
}
