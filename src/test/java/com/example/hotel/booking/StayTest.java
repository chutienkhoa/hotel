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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the controlled lifecycle state of a Stay. */
class StayTest {

    /** Confirms a new Stay starts checked in with only the backend check-in timestamp recorded. */
    @Test
    void shouldCreateCheckedInStayWithCheckInTimestampOnly() {
        Stay stay = new Stay(reservation());

        assertEquals(StayStatus.CHECKED_IN, stay.getStatus());
        assertNotNull(stay.getActualCheckInAt());
        assertNull(stay.getActualCheckOutAt());
    }

    /** Confirms the explicit domain operation transitions a checked-in Stay to checked out. */
    @Test
    void shouldCheckOutCheckedInStayThroughExplicitDomainMethod() {
        Stay stay = new Stay(reservation());

        stay.checkOut();

        assertEquals(StayStatus.CHECKED_OUT, stay.getStatus());
        assertNotNull(stay.getActualCheckOutAt());
    }

    /** Confirms a terminal checked-out Stay cannot transition again. */
    @Test
    void shouldRejectInvalidStayTransition() {
        Stay stay = new Stay(reservation());
        stay.checkOut();

        assertThrows(IllegalStateException.class, stay::checkOut);
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
