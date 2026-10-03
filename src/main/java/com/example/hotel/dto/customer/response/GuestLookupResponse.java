package com.example.hotel.dto.customer.response;

import java.time.LocalDate;
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
 * @param dateOfBirth the guest's optional date of birth
 */
public record GuestLookupResponse(
        UUID id,
        String guestCode,
        String fullName,
        String email,
        String phone,
        String nationality,
        LocalDate dateOfBirth) {

    /**
     * Creates a lookup representation without date of birth, for existing fixtures/tests that predate this
     * presentation field. Production mapping always uses the canonical constructor.
     *
     * @param id the guest identifier
     * @param guestCode the guest's unique display code
     * @param fullName the guest's display name
     * @param email the guest's optional email address
     * @param phone the guest's optional phone number
     * @param nationality the guest's optional nationality
     */
    public GuestLookupResponse(
            UUID id, String guestCode, String fullName, String email, String phone, String nationality) {
        this(id, guestCode, fullName, email, phone, nationality, null);
    }

    /**
     * Creates the former code-only lookup representation for source compatibility.
     *
     * @param id Guest identifier
     * @param guestCode Guest display code
     */
    public GuestLookupResponse(UUID id, String guestCode) {
        this(id, guestCode, null, null, null, null, null);
    }
}
