package com.example.hotel.controller.room;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.service.room.RoomQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the read-only room lookup data required by reservation creation clients.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomLookupController {

    private final RoomQueryService roomQueryService;

    /**
     * Creates the controller with the service used to load room lookup data.
     *
     * @param roomQueryService service used to load rooms
     */
    public RoomLookupController(RoomQueryService roomQueryService) {
        this.roomQueryService = roomQueryService;
    }

    /**
     * Returns rooms that can be selected while creating a reservation.
     *
     * @return the room lookup entries
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    List<RoomLookupResponse> findAllForReservationCreation() {
        return roomQueryService.findAllForReservationCreation();
    }
}
