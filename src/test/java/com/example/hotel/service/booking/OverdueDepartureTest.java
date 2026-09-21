package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Verifies the single derived overdue-departure rule and its overdue-days formula. */
class OverdueDepartureTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    /** Confirms 0 days for today or the future, 1 for yesterday, 2 for two days ago, and a matching boolean. */
    @Test
    void shouldDeriveOverdueDaysFromThePlannedCheckOut() {
        assertEquals(0, OverdueDeparture.overdueDays(TODAY, TODAY));
        assertEquals(0, OverdueDeparture.overdueDays(TODAY.plusDays(3), TODAY));
        assertEquals(1, OverdueDeparture.overdueDays(TODAY.minusDays(1), TODAY));
        assertEquals(2, OverdueDeparture.overdueDays(TODAY.minusDays(2), TODAY));
        assertFalse(OverdueDeparture.isOverdue(TODAY, TODAY));
        assertFalse(OverdueDeparture.isOverdue(TODAY.plusDays(1), TODAY));
        assertTrue(OverdueDeparture.isOverdue(TODAY.minusDays(1), TODAY));
    }

    /** Confirms the boolean is true exactly when the day count is at least one, across a month boundary. */
    @Test
    void shouldAgreeAcrossAMonthBoundary() {
        LocalDate planned = LocalDate.of(2026, 9, 30);
        assertEquals(2, OverdueDeparture.overdueDays(planned, LocalDate.of(2026, 10, 2)));
        assertTrue(OverdueDeparture.isOverdue(planned, LocalDate.of(2026, 10, 2)));
    }
}
