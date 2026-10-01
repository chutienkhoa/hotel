package com.example.hotel.exception;

import java.time.YearMonth;

/**
 * Raised when a report is requested for a month that cannot be reported: a future month, or a month
 * before the first month fully covered by Room inventory history. It is a user-facing rejection, not a
 * data-integrity failure, and carries no display text.
 */
public class ReportPeriodUnavailableException extends RuntimeException {

    /** Why the requested month cannot be reported. */
    public enum Reason {
        FUTURE_MONTH,
        HISTORY_UNAVAILABLE
    }

    private final Reason reason;
    private final YearMonth firstSupportedMonth;

    /**
     * Creates the exception.
     *
     * @param reason why the month is rejected
     * @param firstSupportedMonth first fully supported month for {@code HISTORY_UNAVAILABLE}, otherwise {@code null}
     * @param message English detail for logs
     */
    public ReportPeriodUnavailableException(Reason reason, YearMonth firstSupportedMonth, String message) {
        super(message);
        this.reason = reason;
        this.firstSupportedMonth = firstSupportedMonth;
    }

    /**
     * Returns why the month is rejected.
     *
     * @return the rejection reason
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * Returns the first fully supported month.
     *
     * @return the first supported month, or {@code null} unless the reason is {@code HISTORY_UNAVAILABLE}
     */
    public YearMonth getFirstSupportedMonth() {
        return firstSupportedMonth;
    }
}
