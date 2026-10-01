package com.example.hotel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/** Verifies the Reservation guest-composition invariants: adults at least 1, children at least 0, party size derived. */
class ReservationGuestCompositionTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);

    /** Confirms valid compositions are accepted and the party size is adults plus children. */
    @ParameterizedTest
    @CsvSource({"1,0,1", "2,1,3", "3,0,3", "3,1,4"})
    void shouldAcceptValidCompositionAndDerivePartySize(int adults, int children, int partySize) {
        Reservation reservation = draft(adults, children);

        assertEquals(adults, reservation.getAdultCount());
        assertEquals(children, reservation.getChildCount());
        assertEquals(partySize, reservation.getPartySize());
    }

    /** Confirms zero or negative adults and negative children are rejected at creation. */
    @ParameterizedTest
    @CsvSource({"0,0", "0,1", "-1,0", "1,-1", "2,-3"})
    void shouldRejectInvalidCompositionAtCreation(int adults, int children) {
        assertThrows(IllegalArgumentException.class, () -> draft(adults, children));
    }

    /** Confirms the party size is independent of rooms, RoomType capacity and Guest profiles (no capacity check). */
    @Test
    void shouldAllowAPartyLargerThanTheBookedRoomCapacity() {
        Reservation reservation = draft(3, 1);
        RoomType doubleType = Mockito.mock(RoomType.class);
        Mockito.when(doubleType.getCapacity()).thenReturn(2);
        Room room = Room.create(UUID.randomUUID(), "101", doubleType, "1");
        reservation.addRoom(new ReservationRoom(reservation, room, IN, OUT, BigDecimal.TEN));

        assertEquals(4, reservation.getPartySize());
    }

    /** Confirms fixture-style constructors fall back to the documented V1 default of 1 adult and 0 children. */
    @Test
    void shouldDefaultFixtureConstructorsToOneAdultAndNoChildren() {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R-1", guest(), IN, OUT, "VND", null);

        assertEquals(Reservation.DEFAULT_ADULT_COUNT, reservation.getAdultCount());
        assertEquals(Reservation.DEFAULT_CHILD_COUNT, reservation.getChildCount());
    }

    /** Confirms a DRAFT's counts can be changed, even beyond room capacity, and are validated. */
    @Test
    void shouldUpdateCompositionOfADraft() {
        Reservation reservation = draft(2, 0);

        reservation.updateDraft(guest(), IN, OUT, 3, 1, BookingSource.DIRECT, null, "VND", null, List.of());

        assertEquals(3, reservation.getAdultCount());
        assertEquals(1, reservation.getChildCount());
        assertThrows(IllegalArgumentException.class,
                () -> reservation.updateDraft(guest(), IN, OUT, 0, 0, BookingSource.DIRECT, null, "VND", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> reservation.updateDraft(guest(), IN, OUT, 1, -1, BookingSource.DIRECT, null, "VND", null, List.of()));
        assertEquals(3, reservation.getAdultCount());
    }

    /** Confirms the update overload without counts keeps the existing composition. */
    @Test
    void shouldKeepCompositionWhenUpdatedWithoutCounts() {
        Reservation reservation = draft(2, 1);

        reservation.updateDraft(guest(), IN, OUT, BookingSource.DIRECT, null, "VND", null, List.of());

        assertEquals(2, reservation.getAdultCount());
        assertEquals(1, reservation.getChildCount());
    }

    /** Confirms a CONFIRMED Reservation cannot have its composition changed through draft editing. */
    @Test
    void shouldNotAllowChangingCompositionAfterConfirmation() {
        Reservation reservation = draft(2, 0);
        reservation.confirm();

        assertThrows(IllegalStateException.class, () -> reservation.updateDraft(
                guest(), IN, OUT, 4, 2, BookingSource.DIRECT, null, "VND", null, List.of()));
        assertEquals(2, reservation.getAdultCount());
        assertEquals(0, reservation.getChildCount());
    }

    private static Reservation draft(int adults, int children) {
        return new Reservation(UUID.randomUUID(), "R-1", guest(), IN, OUT, adults, children,
                BookingSource.DIRECT, null, "VND", null);
    }

    private static Guest guest() {
        return Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);
    }
}
