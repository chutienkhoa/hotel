package com.example.hotel.controller.customer;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.service.customer.GuestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the authorized REST operations for managed guest profiles. */
@RestController
@RequestMapping("/api/guests")
public class GuestController {

    private final GuestService guestService;

    /**
     * Creates the guest API controller with the service that owns guest operations.
     *
     * @param guestService service used to manage guest profiles
     */
    public GuestController(GuestService guestService) {
        this.guestService = guestService;
    }

    /**
     * Returns all guest profiles available to guest-management users.
     *
     * @return the guest list
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    List<GuestResponse> findAll() {
        return guestService.findAll();
    }

    /**
     * Returns one guest profile by identifier.
     *
     * @param id guest identifier
     * @return the guest profile
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    GuestResponse findById(@PathVariable UUID id) {
        return guestService.findById(id);
    }

    /**
     * Creates a guest from mutable client profile fields.
     *
     * @param request validated guest creation data
     * @return the created guest profile
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    GuestResponse create(@Valid @RequestBody GuestCreateRequest request) {
        return guestService.create(request);
    }

    /**
     * Updates a guest's mutable profile fields without accepting its guest code or audit fields.
     *
     * @param id guest identifier
     * @param request validated guest update data
     * @return the updated guest profile
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_GUEST')")
    GuestResponse update(@PathVariable UUID id, @Valid @RequestBody GuestUpdateRequest request) {
        return guestService.update(id, request);
    }
}
