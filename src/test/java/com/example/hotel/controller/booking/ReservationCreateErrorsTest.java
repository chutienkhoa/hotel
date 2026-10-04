package com.example.hotel.controller.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.exception.LocalizedResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.server.ResponseStatusException;

/** Verifies where a Create Reservation rejection is placed on the form and how the dialog summary is ordered. */
class ReservationCreateErrorsTest {

    private static final UUID FIRST_ROOM = UUID.randomUUID();
    private static final UUID SECOND_ROOM = UUID.randomUUID();

    private final CreateRequest form = new CreateRequest(null, null, null, 1, 0, null, null, "VND", null,
            List.of(new RoomRequest(FIRST_ROOM, BigDecimal.ONE), new RoomRequest(SECOND_ROOM, BigDecimal.ONE)),
            List.of());

    private static LocalizedResponseStatusException rejection(String key, Object... arguments) {
        return new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST, key, "reason", arguments);
    }

    /** Confirms field-specific rejections resolve to the field a user can correct. */
    @Test
    void shouldPlaceRejectionsOnTheirFields() {
        assertEquals("otaBookingReference", ReservationCreateErrors.fieldFor(
                rejection("reservation.ota.error.duplicateIdentity", "AGODA", "1"), form));
        assertEquals("otaBookingReference", ReservationCreateErrors.fieldFor(
                rejection("reservation.ota.error.referenceRequired"), form));
        assertEquals("checkOutDate", ReservationCreateErrors.fieldFor(
                rejection("reservation.create.error.checkOutAfterCheckIn"), form));
        assertEquals("guestId", ReservationCreateErrors.fieldFor(rejection("reservation.create.error.guestNotFound"), form));
        assertEquals("rooms", ReservationCreateErrors.fieldFor(rejection("reservation.create.error.duplicateRoom"), form));
        assertEquals("accompanyingGuestIds", ReservationCreateErrors.fieldFor(
                rejection("reservation.create.error.accompanyingIsPrimary"), form));
    }

    /** Confirms a Room rejection lands on the row holding that Room, whichever row it is. */
    @Test
    void shouldPlaceRoomRejectionsOnTheRowOfTheNamedRoom() {
        assertEquals("rooms[1].roomId", ReservationCreateErrors.fieldFor(
                rejection("reservation.create.error.roomNotBookable", "102", SECOND_ROOM), form));
        assertEquals("rooms[0].nightlyRate", ReservationCreateErrors.fieldFor(
                rejection("reservation.create.error.nightlyRateScale", "101", FIRST_ROOM), form));
    }

    /** Confirms a rejection with no single field, a non-localized one, or an unknown Room has no field. */
    @Test
    void shouldLeaveFormLevelRejectionsWithoutAField() {
        assertNull(ReservationCreateErrors.fieldFor(rejection("reservation.create.error.roomNotFound"), form));
        assertNull(ReservationCreateErrors.fieldFor(new ResponseStatusException(HttpStatus.BAD_REQUEST, "raw"), form));
        assertNull(ReservationCreateErrors.fieldFor(
                rejection("reservation.create.error.roomNotBookable", "999", UUID.randomUUID()), form));
    }

    /** Confirms the summary follows page order, prefixes Room rows, drops duplicates and puts form-level errors last. */
    @Test
    void shouldOrderTheSummaryByPageOrderAndPrefixRoomRows() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(form, "reservationForm");
        result.reject("failure", "Form level problem");
        result.rejectValue("notes", "x", "Notes problem");
        result.rejectValue("rooms[1].nightlyRate", "x", "Rate problem");
        result.rejectValue("rooms[0].roomId", "x", "Room problem");
        result.rejectValue("source", "x", "Source problem");
        result.rejectValue("guestId", "x", "Guest problem");
        result.rejectValue("rooms[0].nightlyRate", "x", "Rate problem");

        List<String> summary = ReservationCreateErrors.summary(
                result, error -> error.getDefaultMessage(), (row, message) -> "Row " + row + ": " + message);

        assertEquals(List.of("Guest problem", "Source problem", "Row 1: Room problem", "Row 1: Rate problem",
                "Row 2: Rate problem", "Notes problem", "Form level problem"), summary);
    }
}
