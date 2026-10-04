package com.example.hotel.controller.booking;

import com.example.hotel.dto.customer.response.GuestSearchOptionResponse;
import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the session-authenticated JSON that the Create Reservation page loads on demand: a bounded Guest search and
 * the Rooms bookable for the selected stay dates. The browser session cannot call the stateless, JWT-only
 * {@code /api/**} lookups, so these routes delegate to the same services those lookups use. Both are read-only and
 * UX guidance only; creating the Reservation and confirming it re-validate everything.
 */
@Controller
@RequestMapping("/reservations/new")
public class ReservationCreateLookupController {

    private final GuestQueryService guestQueryService;
    private final RoomAvailabilityService roomAvailability;

    /**
     * Creates the controller.
     *
     * @param guestQueryService source of the capped Guest search
     * @param roomAvailability the single source of booking availability for a stay period
     */
    public ReservationCreateLookupController(
            GuestQueryService guestQueryService, RoomAvailabilityService roomAvailability) {
        this.guestQueryService = guestQueryService;
        this.roomAvailability = roomAvailability;
    }

    /**
     * Searches Guest profiles by code, name, phone or email. Every reusable profile is eligible, including a Guest who
     * has completed an earlier stay.
     *
     * @param query free-text search term; a blank term returns no Guests
     * @return at most ten matching Guests, narrowed to the fields the search displays
     */
    @GetMapping("/guests")
    @ResponseBody
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public List<GuestSearchOptionResponse> searchGuests(@RequestParam(required = false) String query) {
        return guestQueryService.searchForReservationCreation(query).stream()
                .map(GuestSearchOptionResponse::from)
                .toList();
    }

    /**
     * Lists the Rooms that can be booked for {@code [checkInDate, checkOutDate)}: bookable inventory without an
     * inventory conflict, each with its Room Type and adult capacity. A Room that is OCCUPIED, DIRTY or CLEANING today
     * is still offered for a period that does not overlap an existing booking.
     *
     * @param checkInDate inclusive check-in date
     * @param checkOutDate exclusive check-out date, after the check-in date
     * @return the bookable Rooms ordered by room number
     * @throws ResponseStatusException when a date is missing or check-out is not after check-in
     */
    @GetMapping("/rooms")
    @ResponseBody
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    public List<RoomLookupResponse> bookableRooms(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        if (checkInDate == null || checkOutDate == null || !checkOutDate.isAfter(checkInDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "checkInDate and checkOutDate are required and check-out must be after check-in");
        }
        return roomAvailability.bookableRoomsForPeriod(checkInDate, checkOutDate);
    }
}
