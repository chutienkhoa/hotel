package com.example.hotel.dto.customer.response;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Exposes guest profile data without server-controlled audit information.
 *
 * @param id guest identifier
 * @param guestCode immutable backend-generated guest code
 * @param firstName guest first name
 * @param lastName guest last name
 * @param email guest email address
 * @param phone guest phone number
 * @param nationality guest nationality
 * @param dateOfBirth guest date of birth
 * @param idDocumentNumber guest national ID / passport number
 * @param address guest address
 */
public record GuestResponse(
        UUID id,
        String guestCode,
        String firstName,
        String lastName,
        String email,
        String phone,
        String nationality,
        LocalDate dateOfBirth,
        String idDocumentNumber,
        String address) {

    /**
     * Creates a response without an ID / Passport Number.
     *
     * @param id guest identifier
     * @param guestCode immutable guest code
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param address guest address
     */
    public GuestResponse(
            UUID id,
            String guestCode,
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String address) {
        this(id, guestCode, firstName, lastName, email, phone, nationality, dateOfBirth, null, address);
    }
}
