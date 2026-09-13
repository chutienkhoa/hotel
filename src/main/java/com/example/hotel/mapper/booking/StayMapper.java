package com.example.hotel.mapper.booking;

import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.Stay;
import org.springframework.stereotype.Component;

/** Converts Stay entities into presentation-safe response DTOs. */
@Component
public class StayMapper {

    /**
     * Converts one Stay into its Folio presentation representation.
     *
     * @param stay persisted Stay to convert
     * @return the mapped Stay response
     */
    public StayResponse toResponse(Stay stay) {
        return new StayResponse(
                stay.getId(),
                stay.getStatus().name(),
                stay.getActualCheckInAt(),
                stay.getActualCheckOutAt());
    }
}
