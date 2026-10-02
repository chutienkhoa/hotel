package com.example.hotel.dto.common.response;

import com.example.hotel.dto.booking.response.FrontDeskStayRow;

/**
 * Wraps one {@link FrontDeskStayRow} with a Dashboard-only presentation value: the number of nights,
 * which is derived for display and is never a stored domain field. For Currently Staying, {@code
 * nights} is the number of nights elapsed from actual check-in to the hotel's current date. For Today's
 * Departures, {@code nights} is the full length of stay, from actual check-in to the planned check-out
 * date.
 *
 * @param row the underlying canonical Front Desk stay row (same business data, same readiness rules)
 * @param nights the derived, presentation-only night count described above
 */
public record DashboardStayRow(FrontDeskStayRow row, long nights) {}
