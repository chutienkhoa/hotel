package com.example.hotel.dto.common.response;

/**
 * Today's Departures KPI data. {@code todayCount} is strictly scoped to {@code plannedCheckOutDate ==
 * hotelToday} and never includes overdue departures, even though overdue departures remain visible in
 * the Today's Departures worklist.
 *
 * @param todayCount count of due departures whose planned check-out date is exactly the hotel's
 *     current date
 * @param needsAttentionCount count of today's departures that need attention (overdue or payment
 *     required), a subset of {@code todayCount}
 */
public record DashboardDeparturesKpi(long todayCount, long needsAttentionCount) {}
