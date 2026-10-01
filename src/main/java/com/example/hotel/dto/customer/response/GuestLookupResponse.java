package com.example.hotel.dto.customer.response;

import java.util.UUID;

/**
 * Supplies the minimal guest identity data required by the reservation create form.
 *
 * @param id the guest identifier submitted in a reservation request
 * @param guestCode the guest's unique display code
 * @param fullName the guest's display name
 * @param email the guest's optional email address
 * @param phone the guest's optional phone number
 * @param nationality the guest's optional nationality
 */
public record GuestLookupResponse(
        UUID id,
        String guestCode,
        String fullName,
        String email,
        String phone,
        String nationality) {

    /**
     * Creates the former code-only lookup representation for source compatibility.
     *
     * @param id Guest identifier
     * @param guestCode Guest display code
     */
    public GuestLookupResponse(UUID id, String guestCode) {
        this(id, guestCode, null, null, null, null);
    }
}
