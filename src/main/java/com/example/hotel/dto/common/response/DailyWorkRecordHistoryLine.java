package com.example.hotel.dto.common.response;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Exposes one read-only Work History row for the Staff Detail screen. Represents an actual
 * persisted Daily Work Record entry; a missing calendar date is never synthesized into a row.
 *
 * @param workDate calendar day the entry was recorded for
 * @param startTime manually entered Work Check-in time
 * @param endTime manually entered Work Check-out time
 * @param workingTime derived {@code "9h00"}-style duration, never persisted
 * @param notes optional notes, or {@code null} when not supplied
 */
public record DailyWorkRecordHistoryLine(
        LocalDate workDate, LocalTime startTime, LocalTime endTime, String workingTime, String notes) {}
