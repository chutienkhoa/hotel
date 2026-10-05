package com.example.hotel.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

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
     * Formats the calendar date of an Instant in the hotel display timezone as {@code dd/MM/yyyy}.
     *
     * @param instant the instant to format, or {@code null}
     * @return the formatted display string, or {@code null} when the instant is {@code null}
     */
    public static String formatInstantDate(Instant instant) {
        return instant == null ? null : DATE_FORMATTER.format(instant.atZone(DISPLAY_ZONE));
    }

    /**
     * Formats the time of day of an Instant in the hotel display timezone as {@code HH:mm} (24-hour, no seconds).
     *
     * @param instant the instant to format, or {@code null}
     * @return the formatted display string, or {@code null} when the instant is {@code null}
     */
    public static String formatInstantTime(Instant instant) {
        return instant == null ? null : TIME_FORMATTER.format(instant.atZone(DISPLAY_ZONE));
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

    /**
     * Formats a time-only value as {@code HH:mm} (24-hour, no seconds).
     *
     * @param time the time to format, or {@code null}
     * @return the formatted display string, or {@code null} when the time is {@code null}
     */
    public static String formatTime(LocalTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }
}
