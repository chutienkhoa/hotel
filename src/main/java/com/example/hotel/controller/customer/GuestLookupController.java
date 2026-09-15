package com.example.hotel.controller.customer;

import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.service.customer.GuestQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the read-only guest lookup data required by reservation creation clients.
 */
@RestController
@RequestMapping("/api/guests")
public class GuestLookupController {

    private final GuestQueryService guestQueryService;

    /**
     * Creates the controller with the service used to load guest lookup data.
     *
     * @param guestQueryService service used to load guests
     */
    public GuestLookupController(GuestQueryService guestQueryService) {
        this.guestQueryService = guestQueryService;
    }

    /**
     * Searches guests that can be selected while creating a reservation.
     *
     * @param query optional Guest code, full-name, phone, or email search text
     * @return the capped Guest lookup entries
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    List<GuestLookupResponse> searchForReservationCreation(
            @RequestParam(required = false) String query) {
        return guestQueryService.searchForReservationCreation(query);
    }
}
