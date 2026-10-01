package com.example.hotel.controller.room;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.service.room.RoomAvailabilityService;
import com.example.hotel.service.room.RoomQueryService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exposes the read-only room lookup data required by reservation creation clients.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomLookupController {

    private final RoomQueryService roomQueryService;
    private final RoomAvailabilityService roomAvailability;

    /**
     * Creates the controller with the services used to load room lookup data.
     *
     * @param roomQueryService service used to load rooms
     * @param roomAvailability service that answers booking availability for a requested period
     */
    public RoomLookupController(RoomQueryService roomQueryService, RoomAvailabilityService roomAvailability) {
        this.roomQueryService = roomQueryService;
        this.roomAvailability = roomAvailability;
    }

    /**
     * Returns rooms that can be selected while creating a reservation. Without dates it lists bookable inventory
     * independent of current RoomStatus; with both dates it also excludes Rooms with an overlapping Reservation
     * for {@code [checkInDate, checkOutDate)}.
     *
     * @param checkInDate optional inclusive check-in date
     * @param checkOutDate optional exclusive check-out date; required together with {@code checkInDate}
     * @return the room lookup entries
     * @throws ResponseStatusException if only one date is given or check-out is not after check-in
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    List<RoomLookupResponse> lookup(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        if (checkInDate == null && checkOutDate == null) {
            return roomQueryService.findAllForReservationCreation();
        }
        if (checkInDate == null || checkOutDate == null || !checkOutDate.isAfter(checkInDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "checkInDate and checkOutDate are required together and check-out must be after check-in");
        }
        return roomAvailability.bookableRoomsForPeriod(checkInDate, checkOutDate);
    }
}
