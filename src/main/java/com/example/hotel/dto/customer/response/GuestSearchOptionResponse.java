package com.example.hotel.dto.customer.response;

import java.util.UUID;

/**
 * One Guest offered by the Create Reservation Primary / Accompanying Guest search. It carries only what staff need to
 * recognise the right profile; date of birth and ID / passport number are deliberately absent.
 *
 * @param id the Guest identifier submitted in a reservation request
 * @param guestCode the Guest's unique display code
 * @param fullName the Guest's display name
 * @param email the Guest's optional email address
 * @param phone the Guest's optional phone number
 * @param nationality the Guest's optional nationality
 */
public record GuestSearchOptionResponse(
        UUID id, String guestCode, String fullName, String email, String phone, String nationality) {

    /**
     * Narrows a Guest lookup entry to the fields the search shows.
     *
     * @param guest the lookup entry to narrow
     * @return the search option for the same Guest
     */
    public static GuestSearchOptionResponse from(GuestLookupResponse guest) {
        return new GuestSearchOptionResponse(
                guest.id(), guest.guestCode(), guest.fullName(), guest.email(), guest.phone(), guest.nationality());
    }
}
