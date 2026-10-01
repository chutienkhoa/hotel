package com.example.hotel.mapper.common;

import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.entity.common.Staff;
import org.springframework.stereotype.Component;

/** Converts Staff Management entities into client-safe responses. */
@Component
public class StaffMapper {

    /**
     * Maps a Staff entity to its client-safe representation.
     *
     * @param staff source Staff member
     * @return Staff response without audit fields
     */
    public StaffResponse toResponse(Staff staff) {
        return new StaffResponse(
                staff.getId(),
                staff.getStaffCode(),
                staff.getFirstName(),
                staff.getLastName(),
                staff.getPhone(),
                staff.getEmail(),
                staff.getPosition(),
                staff.getStartDate(),
                staff.isActive(),
                staff.getNotes());
    }
}
