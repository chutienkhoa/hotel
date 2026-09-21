package com.example.hotel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the controlled lifecycle state of a Stay. */
class StayTest {

    /** Confirms a new Stay starts checked in with only the backend check-in timestamp recorded. */
    @Test
    void shouldCreateCheckedInStayWithCheckInTimestampOnly() {
        Stay stay = new Stay(reservation(), java.time.Instant.parse("2026-09-21T03:00:00Z"));

        assertEquals(StayStatus.CHECKED_IN, stay.getStatus());
        assertNotNull(stay.getActualCheckInAt());
        assertNull(stay.getActualCheckOutAt());
    }

    /** Confirms the supplied Instant is recorded exactly, and a missing one is rejected. */
    @Test
    void shouldRecordTheSuppliedCheckInInstantExactly() {
        Instant checkedInAt = Instant.parse("2026-09-21T03:00:00Z");

        assertEquals(checkedInAt, new Stay(reservation(), checkedInAt).getActualCheckInAt());
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new Stay(reservation(), null));
    }

    /** Confirms the explicit domain operation transitions a checked-in Stay to checked out. */
    @Test
    void shouldCheckOutCheckedInStayThroughExplicitDomainMethod() {
        Stay stay = new Stay(reservation(), java.time.Instant.parse("2026-09-21T03:00:00Z"));

        stay.checkOut(Instant.now());

        assertEquals(StayStatus.CHECKED_OUT, stay.getStatus());
        assertNotNull(stay.getActualCheckOutAt());
    }

    /**
     * Confirms checkOut records exactly the caller-supplied Instant, never an internally computed
     * one, so the entity never bypasses the authoritative Clock the calling service applies.
     */
    @Test
    void shouldRecordExactlyTheSuppliedCheckOutInstant() {
        Stay stay = new Stay(reservation(), java.time.Instant.parse("2026-09-21T03:00:00Z"));
        Instant suppliedInstant = Instant.parse("2026-09-18T03:00:00Z");

        stay.checkOut(suppliedInstant);

        assertEquals(suppliedInstant, stay.getActualCheckOutAt());
    }

    /** Confirms a terminal checked-out Stay cannot transition again. */
    @Test
    void shouldRejectInvalidStayTransition() {
        Stay stay = new Stay(reservation(), java.time.Instant.parse("2026-09-21T03:00:00Z"));
        stay.checkOut(Instant.now());

        assertThrows(IllegalStateException.class, () -> stay.checkOut(Instant.now()));
        assertEquals(StayStatus.CHECKED_OUT, stay.getStatus());
    }

    /** Confirms the entity has no unrestricted status setter. */
    @Test
    void shouldNotExposeArbitraryStayStatusSetter() {
        assertFalse(
                Arrays.stream(Stay.class.getMethods())
                        .map(Method::getName)
                        .anyMatch("setStatus"::equals));
    }

    /**
     * Creates a Reservation fixture required to construct a Stay.
     *
     * @return a draft reservation fixture
     */
    private Reservation reservation() {
        return new Reservation(
                UUID.randomUUID(),
                "R20260911-000001",
                null,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 12),
                "JPY",
                null);
    }
}
