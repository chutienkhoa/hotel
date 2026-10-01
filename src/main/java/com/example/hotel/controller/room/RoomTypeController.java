package com.example.hotel.controller.room;

import com.example.hotel.dto.room.response.RoomTypeResponse;
import com.example.hotel.service.room.RoomTypeQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes read-only RoomType data required by Room Management. */
@RestController
@RequestMapping("/api/room-types")
public class RoomTypeController {

    private final RoomTypeQueryService roomTypeQueryService;

    /**
     * Creates the RoomType API controller with the read-only query service.
     *
     * @param roomTypeQueryService service used to load RoomTypes
     */
    public RoomTypeController(RoomTypeQueryService roomTypeQueryService) {
        this.roomTypeQueryService = roomTypeQueryService;
    }

    /**
     * Returns RoomTypes that may be selected in approved Room Management operations.
     *
     * @return the read-only RoomType list
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_ROOM')")
    List<RoomTypeResponse> findAll() {
        return roomTypeQueryService.findAll();
    }
}
