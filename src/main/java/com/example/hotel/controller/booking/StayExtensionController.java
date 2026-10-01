package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.service.booking.StayExtensionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point of the narrow Stay Extension operation (no generic Reservation date mutation). */
@RestController
@RequestMapping("/api/reservations")
public class StayExtensionController {

    private final StayExtensionService service;

    /**
     * Creates the controller.
     *
     * @param service owner of the extension operation
     */
    StayExtensionController(StayExtensionService service) {
        this.service = service;
    }

    /**
     * Extends the whole Stay of a CHECKED_IN Reservation to a later planned check-out date.
     *
     * @param id reservation identifier
     * @param request expected current check-out date and new check-out date
     * @return the Reservation response
     */
    @PostMapping("/{id}/stay-extension")
    @PreAuthorize("hasAuthority('PERM_EXTEND_STAY')")
    Response extend(@PathVariable UUID id, @Valid @RequestBody StayExtensionRequest request) {
        return service.extend(id, request);
    }
}
