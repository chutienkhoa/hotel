package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/** Verifies the sort whitelists: safe mapping, direction handling, secondary ordering and default fallback. */
class TableSortsTest {

    private static List<String> describe(Sort sort) {
        return sort.stream().map(order -> order.getProperty() + ":" + order.getDirection()).toList();
    }

    /** Confirms defaults are preserved when nothing valid is requested. */
    @Test
    void shouldKeepApprovedDefaultOrderings() {
        assertEquals(List.of("checkInDate:DESC", "reservationNumber:ASC"), describe(TableSorts.RESERVATION.resolve(null, null)));
        assertEquals(List.of("guestCode:ASC"), describe(TableSorts.GUEST.resolve(null, null)));
        assertEquals(List.of("roomNumber:ASC"), describe(TableSorts.ROOM.resolve(null, null)));
        assertEquals(List.of("expenseDate:DESC", "id:DESC"), describe(TableSorts.EXPENSE.resolve(null, null)));
        assertEquals(List.of("revenueDate:DESC", "id:DESC"), describe(TableSorts.ADDITIONAL_REVENUE.resolve(null, null)));
    }

    /** Confirms valid keys map through the whitelist in both directions with a deterministic secondary order. */
    @Test
    void shouldResolveValidKeysAscendingAndDescending() {
        assertEquals(List.of("checkInDate:ASC", "reservationNumber:ASC"), describe(TableSorts.RESERVATION.resolve("checkInDate", "asc")));
        assertEquals(List.of("checkOutDate:DESC", "reservationNumber:ASC"), describe(TableSorts.RESERVATION.resolve("checkOutDate", "DESC")));
        assertEquals(List.of("reservationNumber:DESC"), describe(TableSorts.RESERVATION.resolve("reservationNumber", "desc")));
        assertEquals(List.of("roomType.code:ASC", "roomNumber:ASC"), describe(TableSorts.ROOM.resolve("roomType", "asc")));
        assertEquals(List.of("lastName:DESC", "guestCode:ASC"), describe(TableSorts.GUEST.resolve("lastName", "desc")));
        assertEquals(List.of("amount:ASC", "expenseDate:DESC", "id:DESC"), describe(TableSorts.EXPENSE.resolve("amount", "asc")));
        assertEquals(List.of("expenseDate:ASC", "id:DESC"), describe(TableSorts.EXPENSE.resolve("date", "asc")));
        assertEquals(List.of("revenueDate:DESC", "id:DESC"), describe(TableSorts.ADDITIONAL_REVENUE.resolve("date", "desc")));
    }

    /** Confirms an unknown key, an unknown direction, or raw entity/injection text falls back to the default. */
    @Test
    void shouldFallBackToDefaultForInvalidKeyOrDirection() {
        Sort reservationDefault = TableSorts.RESERVATION.resolve(null, null);
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("unknown", "asc"));
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("checkInDate", "sideways"));
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("checkInDate", null));
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("guest.passwordHash", "asc"));
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("checkInDate; DROP TABLE", "asc"));
        assertEquals(reservationDefault, TableSorts.RESERVATION.resolve("CHECKINDATE", "asc"));
    }

    /** Confirms Check-in and Check-out accept only their approved subsets. */
    @Test
    void shouldRestrictCheckInAndCheckOutKeys() {
        assertEquals("checkInDate", TableSorts.CHECK_IN.key("checkInDate", "asc"));
        assertNull(TableSorts.CHECK_IN.key("checkOutDate", "asc"));
        assertNull(TableSorts.CHECK_IN.key("status", "asc"));
        assertEquals("checkOutDate", TableSorts.CHECK_OUT.key("checkOutDate", "desc"));
        assertNull(TableSorts.CHECK_OUT.key("checkInDate", "desc"));
        assertEquals("reservationNumber", TableSorts.CHECK_OUT.key("reservationNumber", "asc"));
    }

    /** Confirms the active direction is reported only for a fully valid request. */
    @Test
    void shouldReportActiveDirectionOnlyWhenValid() {
        assertEquals("desc", TableSorts.GUEST.activeDirection("firstName", "DESC"));
        assertNull(TableSorts.GUEST.activeDirection("firstName", "up"));
        assertNull(TableSorts.GUEST.activeDirection("nope", "asc"));
    }
}
