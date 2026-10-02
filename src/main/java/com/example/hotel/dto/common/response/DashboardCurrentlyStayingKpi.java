package com.example.hotel.dto.common.response;

/**
 * Currently Staying KPI data: current physical guest headcount, the current occupied-room count, and
 * the guest-headcount delta versus the same point in time yesterday.
 *
 * @param guests current physical guest headcount (adults plus children) across every in-house Stay
 * @param occupiedRooms current count of Rooms with operational status OCCUPIED
 * @param guestDeltaVsYesterday {@code guests} minus the equivalent guest headcount evaluated at the
 *     hotel-local start of today (end of yesterday); positive means more guests than yesterday
 */
public record DashboardCurrentlyStayingKpi(long guests, long occupiedRooms, long guestDeltaVsYesterday) {

    /**
     * Formats {@link #guestDeltaVsYesterday} with an explicit leading sign for positive values, for
     * direct use as the Dashboard trend message argument (presentation-only; never persisted).
     *
     * @return the delta as a signed string, e.g. {@code "+2"}, {@code "-1"}, or {@code "0"}
     */
    public String signedGuestDeltaVsYesterday() {
        return (guestDeltaVsYesterday > 0 ? "+" : "") + guestDeltaVsYesterday;
    }

    /**
     * Builds the full CSS class attribute value for the Dashboard trend indicator, so the template never
     * needs a conditional expression for it (and never needs a BEM {@code __}-containing string literal
     * inside a Thymeleaf expression, which collides with Thymeleaf's own preprocessing syntax).
     *
     * @return {@code "kpi-card__trend kpi-card__trend--up"}, {@code "...--down"}, or {@code "...--flat"}
     */
    public String trendCssClass() {
        String modifier;
        if (guestDeltaVsYesterday > 0) {
            modifier = "up";
        } else {
            modifier = guestDeltaVsYesterday < 0 ? "down" : "flat";
        }
        return "kpi-card__trend kpi-card__trend--" + modifier;
    }
}
