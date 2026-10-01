package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Verifies the centralized hotel display date/time formatting standard. */
class DisplayFormatsTest {

    /** Confirms an Instant is converted to the hotel display timezone before formatting. */
    @Test
    void shouldConvertInstantToHotelTimezoneBeforeFormatting() {
        // 2026-09-15T14:40:41Z is 21:40 in Asia/Ho_Chi_Minh (UTC+7).
        String formatted = DisplayFormats.formatDateTime(Instant.parse("2026-09-15T14:40:41Z"));

        assertEquals("15/09/2026 21:40", formatted);
    }

    /** Confirms the date-time format is dd/MM/yyyy HH:mm using 24-hour time. */
    @Test
    void shouldFormatDateTimeAsDdMmYyyyHhMmIn24HourTime() {
        // 23:40 UTC+7 must never render as "11:40 PM".
        String formatted = DisplayFormats.formatDateTime(Instant.parse("2026-09-15T16:40:00Z"));

        assertEquals("15/09/2026 23:40", formatted);
        assertFalse(formatted.contains("AM"));
        assertFalse(formatted.contains("PM"));
    }

    /** Confirms seconds are never rendered. */
    @Test
    void shouldOmitSeconds() {
        String formatted = DisplayFormats.formatDateTime(Instant.parse("2026-09-15T14:40:41Z"));

        assertEquals("15/09/2026 21:40", formatted);
        assertFalse(formatted.contains(":41"));
    }

    /** Confirms fractional seconds are never rendered. */
    @Test
    void shouldOmitFractionalSeconds() {
        String formatted = DisplayFormats.formatDateTime(Instant.parse("2026-09-15T14:40:41.407334Z"));

        assertEquals("15/09/2026 21:40", formatted);
        assertFalse(formatted.contains("."));
    }

    /** Confirms no ISO-8601 syntax (T, Z, dashes) leaks into the formatted display string. */
    @Test
    void shouldNotRenderIsoSyntax() {
        String formatted = DisplayFormats.formatDateTime(Instant.parse("2026-09-15T14:40:41Z"));

        assertFalse(formatted.contains("T"));
        assertFalse(formatted.contains("Z"));
        assertFalse(formatted.contains("-"));
    }

    /** Confirms a null Instant formats to null rather than throwing. */
    @Test
    void shouldReturnNullForNullInstant() {
        assertNull(DisplayFormats.formatDateTime(null));
    }

    /** Confirms LocalDate values use dd/MM/yyyy, with no time component. */
    @Test
    void shouldFormatDateOnlyAsDdMmYyyy() {
        String formatted = DisplayFormats.formatDate(LocalDate.of(2026, 9, 15));

        assertEquals("15/09/2026", formatted);
    }

    /** Confirms a null LocalDate formats to null rather than throwing. */
    @Test
    void shouldReturnNullForNullDate() {
        assertNull(DisplayFormats.formatDate(null));
    }
}
