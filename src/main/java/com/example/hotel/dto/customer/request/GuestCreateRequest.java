package com.example.hotel.dto.customer.request;

import com.example.hotel.common.validation.CanonicalNationality;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Contains the mutable profile fields supplied when creating a guest.
 *
 * @param firstName guest first name
 * @param lastName guest last name
 * @param email guest email address
 * @param phone guest phone number
 * @param nationality guest nationality
 * @param dateOfBirth guest date of birth; must not be in the future when supplied
 * @param idDocumentNumber optional national ID / passport number
 * @param address guest address
 */
public record GuestCreateRequest(
        @NotBlank(message = "First name is required.") @Size(max = 100) String firstName,
        @NotBlank(message = "Last name is required.") @Size(max = 100) String lastName,
        @Size(max = 255) String email,
        @Size(max = 100) String phone,
        @NotBlank(message = "Nationality is required.") @CanonicalNationality @Size(max = 100) String nationality,
        @PastOrPresent(message = "{validation.guest.dateOfBirth.future}") LocalDate dateOfBirth,
        @Size(max = 50, message = "{validation.guest.idDocumentNumber.max}") String idDocumentNumber,
        String address) {

    /**
     * Creates a request without an ID / Passport Number.
     *
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param address guest address
     */
    public GuestCreateRequest(
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String address) {
        this(firstName, lastName, email, phone, nationality, dateOfBirth, null, address);
    }
}
