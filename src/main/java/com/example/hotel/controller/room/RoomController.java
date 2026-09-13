package com.example.hotel.controller.room;

import com.example.hotel.dto.room.request.RoomCreateRequest;
import com.example.hotel.dto.room.request.RoomUpdateRequest;
import com.example.hotel.dto.room.response.RoomResponse;
import com.example.hotel.service.room.RoomService;
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

/** Exposes the authorized REST operations for Room Management. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    /**
     * Creates the Room API controller with the Room Management service.
     *
     * @param roomService service used to manage room profiles
     */
    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * Returns all room profiles available to Room Management users.
     *
     * @return the room list
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    List<RoomResponse> findAll() {
        return roomService.findAll();
    }

    /**
     * Returns one room profile by identifier.
     *
     * @param id room identifier
     * @return the room profile
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse findById(@PathVariable UUID id) {
        return roomService.findById(id);
    }

    /**
     * Creates a room from the approved mutable profile fields.
     *
     * @param request validated room creation data
     * @return the created room profile
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse create(@Valid @RequestBody RoomCreateRequest request) {
        return roomService.create(request);
    }

    /**
     * Updates the approved mutable profile fields of a room.
     *
     * @param id room identifier
     * @param request validated room update data
     * @return the updated room profile
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse update(@PathVariable UUID id, @Valid @RequestBody RoomUpdateRequest request) {
        return roomService.update(id, request);
    }

    /**
     * Starts cleaning a dirty room.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/start-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse startCleaning(@PathVariable UUID id) {
        return roomService.startCleaning(id);
    }

    /**
     * Finishes cleaning a cleaning room.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/finish-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse finishCleaning(@PathVariable UUID id) {
        return roomService.finishCleaning(id);
    }

    /**
     * Starts maintenance for an available room.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/start-maintenance")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse startMaintenance(@PathVariable UUID id) {
        return roomService.startMaintenance(id);
    }

    /**
     * Finishes maintenance for a room in maintenance.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/finish-maintenance")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse finishMaintenance(@PathVariable UUID id) {
        return roomService.finishMaintenance(id);
    }

    /**
     * Marks an available room out of order.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/mark-out-of-order")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse markOutOfOrder(@PathVariable UUID id) {
        return roomService.markOutOfOrder(id);
    }

    /**
     * Restores an out-of-order room to available service.
     *
     * @param id room identifier
     * @return the transitioned room profile
     */
    @PostMapping("/{id}/restore-to-service")
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    RoomResponse restoreToService(@PathVariable UUID id) {
        return roomService.restoreToService(id);
    }
}
