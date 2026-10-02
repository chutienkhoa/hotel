package com.example.hotel.dto.common.response;

/**
 * Permission flags resolved by the Dashboard controller from the current authentication, used to
 * decide which operational blocks the Dashboard read model includes. {@code VIEW_REPORT} (the
 * Dashboard's own page-level authorization) never implies any of these; each is the exact permission
 * its canonical page already requires.
 *
 * @param canCheckIn gates the Today's Arrivals KPI and table ({@code PERM_CHECK_IN})
 * @param canCheckOut gates the Currently Staying and Today's Departures KPIs and tables ({@code
 *     PERM_CHECK_OUT})
 * @param canManagePayment gates the outstanding-balance amount within Today's Departures ({@code
 *     PERM_MANAGE_PAYMENT})
 * @param canViewBooking gates Recent Reservations ({@code PERM_VIEW_BOOKING})
 */
public record DashboardPermissions(
        boolean canCheckIn, boolean canCheckOut, boolean canManagePayment, boolean canViewBooking) {}
