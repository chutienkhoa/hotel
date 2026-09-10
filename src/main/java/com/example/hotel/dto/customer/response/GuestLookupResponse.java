package com.example.hotel.dto.customer.response;

import java.util.UUID;

/**
 * Supplies the minimal guest identity data required by the reservation create form.
 *
 * @param id the guest identifier submitted in a reservation request
 * @param guestCode the guest's unique display code
 */
public record GuestLookupResponse(UUID id, String guestCode) {
}
