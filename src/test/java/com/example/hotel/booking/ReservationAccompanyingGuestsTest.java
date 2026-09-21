package com.example.hotel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.customer.Guest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the Accompanying Guest invariants of the Reservation aggregate. */
class ReservationAccompanyingGuestsTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);
    private static final UUID USER = UUID.randomUUID();

    /** Confirms a Reservation may have no Accompanying Guests. */
    @Test
    void shouldAllowNoAccompanyingGuests() {
        Reservation reservation = draft(guest("G-1"), 1, 0);

        reservation.replaceAccompanyingGuests(List.of(), USER);

        assertTrue(reservation.getAccompanyingGuests().isEmpty());
    }

    /** Confirms one and several unique Accompanying Guests are accepted. */
    @Test
    void shouldAcceptOneAndSeveralUniqueAccompanyingGuests() {
        Reservation reservation = draft(guest("G-1"), 2, 0);
        Guest b = guest("G-2");
        Guest c = guest("G-3");

        reservation.replaceAccompanyingGuests(List.of(b), USER);
        assertEquals(1, reservation.getAccompanyingGuests().size());

        reservation.replaceAccompanyingGuests(List.of(b, c), USER);
        assertEquals(List.of(b, c), reservation.getAccompanyingGuests());
    }

    /** Confirms the Primary Guest cannot also be an Accompanying Guest. */
    @Test
    void shouldRejectThePrimaryGuestAsAccompanying() {
        Guest primary = guest("G-1");
        Reservation reservation = draft(primary, 1, 0);

        assertThrows(IllegalArgumentException.class, () -> reservation.replaceAccompanyingGuests(List.of(primary), USER));
        assertTrue(reservation.getAccompanyingGuests().isEmpty());
    }

    /** Confirms the same Guest cannot appear twice. */
    @Test
    void shouldRejectDuplicateAccompanyingGuests() {
        Reservation reservation = draft(guest("G-1"), 1, 0);
        Guest b = guest("G-2");

        assertThrows(IllegalArgumentException.class, () -> reservation.replaceAccompanyingGuests(List.of(b, b), USER));
    }

    /** Confirms the number of known profiles is independent of adults, children and party size. */
    @Test
    void shouldNotRequireProfileCountToMatchPartySize() {
        Reservation reservation = draft(guest("G-1"), 3, 1);
        reservation.replaceAccompanyingGuests(List.of(guest("G-2")), USER);

        assertEquals(4, reservation.getPartySize());
        assertEquals(1, reservation.getAccompanyingGuests().size());

        Reservation crowd = draft(guest("G-9"), 1, 0);
        crowd.replaceAccompanyingGuests(List.of(guest("G-A"), guest("G-B"), guest("G-C")), USER);
        assertEquals(1, crowd.getPartySize());
        assertEquals(3, crowd.getAccompanyingGuests().size());
    }

    /** Confirms a Draft update cannot leave the new Primary Guest in the accompanying set. */
    @Test
    void shouldRejectChangingThePrimaryGuestIntoTheAccompanyingSet() {
        Guest primary = guest("G-1");
        Guest companion = guest("G-2");
        Reservation reservation = draft(primary, 2, 0);
        reservation.replaceAccompanyingGuests(List.of(companion), USER);

        // Keeping the existing accompanying set while promoting a companion to primary must fail.
        assertThrows(IllegalArgumentException.class, () -> reservation.updateDraft(
                companion, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of()));
        // Replacing the set in the same operation is valid.
        reservation.updateDraft(companion, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(),
                List.of(primary), USER);

        assertEquals(companion, reservation.getGuest());
        assertEquals(List.of(primary), reservation.getAccompanyingGuests());
    }

    /** Confirms a Draft update can add, remove and replace the accompanying set. */
    @Test
    void shouldAddRemoveAndReplaceOnDraftUpdate() {
        Guest primary = guest("G-1");
        Guest b = guest("G-2");
        Guest c = guest("G-3");
        Reservation reservation = draft(primary, 2, 0);

        reservation.updateDraft(primary, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(), List.of(b), USER);
        assertEquals(List.of(b), reservation.getAccompanyingGuests());
        reservation.updateDraft(primary, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(), List.of(b, c), USER);
        assertEquals(List.of(b, c), reservation.getAccompanyingGuests());
        reservation.updateDraft(primary, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(), List.of(c), USER);
        assertEquals(List.of(c), reservation.getAccompanyingGuests());
        reservation.updateDraft(primary, IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(), List.of(), USER);
        assertTrue(reservation.getAccompanyingGuests().isEmpty());
    }

    /** Confirms updates without an accompanying set keep the existing accompanying Guests. */
    @Test
    void shouldKeepAccompanyingGuestsWhenUpdatedWithoutASet() {
        Guest primary = guest("G-1");
        Guest b = guest("G-2");
        Reservation reservation = draft(primary, 2, 0);
        reservation.replaceAccompanyingGuests(List.of(b), USER);

        reservation.updateDraft(primary, IN, OUT, 3, 1, BookingSource.DIRECT, null, "VND", null, List.of());

        assertEquals(List.of(b), reservation.getAccompanyingGuests());
    }

    /** Confirms a CONFIRMED Reservation cannot have its accompanying set changed. */
    @Test
    void shouldNotChangeAccompanyingGuestsAfterConfirmation() {
        Guest b = guest("G-2");
        Reservation reservation = draft(guest("G-1"), 2, 0);
        reservation.replaceAccompanyingGuests(List.of(b), USER);
        reservation.confirm();

        assertThrows(IllegalStateException.class, () -> reservation.replaceAccompanyingGuests(List.of(), USER));
        assertThrows(IllegalStateException.class, () -> reservation.updateDraft(
                guest("G-3"), IN, OUT, 2, 0, BookingSource.DIRECT, null, "VND", null, List.of(), List.of(), USER));
        assertEquals(List.of(b), reservation.getAccompanyingGuests());
    }

    private static Reservation draft(Guest primary, int adults, int children) {
        return new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, adults, children,
                BookingSource.DIRECT, null, "VND", null);
    }

    private static Guest guest(String code) {
        return Guest.create(UUID.randomUUID(), code, "Ann", "Lee", null, null, "Vietnam", null, null);
    }
}
