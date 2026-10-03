package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Verifies demo open assignments never start after the seeding instant, so a demo Room Change stays temporally valid. */
class DemoDataSeederAssignmentStartTest {

    /** A 14:00 check-in on the business day, seeded at 10:31 local, starts at that day's beginning instead. */
    @Test
    void shouldStartFutureCheckInAtBeginningOfItsBusinessDay() {
        Instant checkedInAt = Instant.parse("2026-10-03T07:00:00Z"); // 14:00 Asia/Ho_Chi_Minh
        Instant seededAt = Instant.parse("2026-10-03T03:31:00Z"); // 10:31 Asia/Ho_Chi_Minh

        Instant start = DemoDataSeeder.demoOpenAssignmentStart(checkedInAt, seededAt);

        assertEquals(Instant.parse("2026-10-02T17:00:00Z"), start); // 00:00 Asia/Ho_Chi_Minh on 2026-10-03
        assertTrue(start.isBefore(seededAt));
    }

    /** A check-in already in the past is kept exactly as seeded, so only the defective case changes. */
    @Test
    void shouldKeepCheckInTimeWhenItIsNotAfterSeeding() {
        Instant checkedInAt = Instant.parse("2026-10-03T03:00:00Z");
        Instant seededAt = Instant.parse("2026-10-03T03:31:00Z");

        assertEquals(checkedInAt, DemoDataSeeder.demoOpenAssignmentStart(checkedInAt, seededAt));
    }
}
