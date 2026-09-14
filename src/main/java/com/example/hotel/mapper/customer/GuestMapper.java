package com.example.hotel.mapper.customer;

import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.dto.customer.response.GuestNationalityDisplay;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.entity.customer.Guest;
import org.springframework.stereotype.Component;

/** Converts guest entities into API and MVC response data. */
@Component
public class GuestMapper {

    /**
     * Maps one guest entity to its client-safe profile representation.
     *
     * @param guest guest entity to map
     * @return the guest response without audit fields
     */
    public GuestResponse toResponse(Guest guest) {
        return new GuestResponse(
                guest.getId(),
                guest.getGuestCode(),
                guest.getFirstName(),
                guest.getLastName(),
                guest.getEmail(),
                guest.getPhone(),
                guest.getNationality(),
                guest.getDateOfBirth(),
                guest.getAddress());
    }

    /**
     * Maps one Guest entity to the compact data required by the paginated Guest list.
     *
     * @param guest Guest entity to map
     * @return Guest list data with presentation-ready nationality information
     */
    public GuestListResponse toListResponse(Guest guest) {
        return new GuestListResponse(
                guest.getId(),
                guest.getGuestCode(),
                guest.getFirstName(),
                guest.getLastName(),
                guest.getEmail(),
                GuestNationalityDisplay.from(guest.getNationality()));
    }
}
