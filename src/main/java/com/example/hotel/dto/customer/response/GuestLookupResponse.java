package com.example.hotel.dto.customer.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * @param idDocumentNumber the guest's optional national ID / passport number; server-rendered into the Walk-in
 *     Guest details only and never serialized to JSON (the lookup API does not expose it)
 */
public record GuestLookupResponse(
        UUID id,
        String guestCode,
        String fullName,
        String email,
        String phone,
        String nationality,
        LocalDate dateOfBirth,
        @JsonIgnore String idDocumentNumber) {

    /**
     * Creates a lookup representation without an ID / Passport Number.
     *
     * @param id the guest identifier
     * @param guestCode the guest's unique display code
     * @param fullName the guest's display name
     * @param email the guest's optional email address
     * @param phone the guest's optional phone number
     * @param nationality the guest's optional nationality
     * @param dateOfBirth the guest's optional date of birth
     */
    public GuestLookupResponse(
            UUID id, String guestCode, String fullName, String email, String phone, String nationality,
            LocalDate dateOfBirth) {
        this(id, guestCode, fullName, email, phone, nationality, dateOfBirth, null);
    }

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
        this(id, guestCode, fullName, email, phone, nationality, null, null);
    }

    /**
     * Creates the former code-only lookup representation for source compatibility.
     *
     * @param id Guest identifier
     * @param guestCode Guest display code
     */
    public GuestLookupResponse(UUID id, String guestCode) {
        this(id, guestCode, null, null, null, null, null, null);
    }
}
