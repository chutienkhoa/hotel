package com.example.hotel.dto.common.response;

/**
 * Today's Arrivals KPI data. {@code todayCount} is strictly scoped to {@code checkInDate ==
 * hotelToday} and never includes overdue arrivals, even though overdue arrivals remain visible in the
 * Today's Arrivals worklist.
 *
 * @param todayCount count of pending arrivals whose check-in date is exactly the hotel's current date
 * @param needsAttentionCount count of today's arrivals that need attention, a subset of {@code
 *     todayCount}
 */
public record DashboardArrivalsKpi(long todayCount, long needsAttentionCount) {}
