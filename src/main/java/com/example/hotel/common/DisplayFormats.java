package com.example.hotel.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Centralizes the approved human-facing date/time display format and the hotel's display
 * timezone for the Thymeleaf UI.
 *
 * <p>This governs presentation only: stored {@link Instant}/{@link LocalDate} values, API JSON
 * serialization, and business calculations are unaffected. The display timezone matches the
 * project's existing operational hotel-time convention (Asia/Ho_Chi_Minh, also used by
 * {@code DashboardClockConfiguration} and {@code DemoDataSeeder}).</p>
 */
public final class DisplayFormats {

    /** The hotel's authoritative display timezone for converting Instant values before formatting. */
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DisplayFormats() {}

    /**
     * Formats an Instant in the hotel display timezone as {@code dd/MM/yyyy HH:mm} (24-hour,
     * no seconds).
     *
     * @param instant the instant to format, or {@code null}
     * @return the formatted display string, or {@code null} when the instant is {@code null}
     */
    public static String formatDateTime(Instant instant) {
        return instant == null ? null : DATE_TIME_FORMATTER.format(instant.atZone(DISPLAY_ZONE));
    }

    /**
     * Formats a date-only value as {@code dd/MM/yyyy}.
     *
     * @param date the date to format, or {@code null}
     * @return the formatted display string, or {@code null} when the date is {@code null}
     */
    public static String formatDate(LocalDate date) {
        return date == null ? null : DATE_FORMATTER.format(date);
    }
}
