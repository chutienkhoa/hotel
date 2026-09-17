package com.example.hotel.dto.customer.request;

import com.example.hotel.common.validation.CanonicalNationality;
import jakarta.validation.constraints.NotBlank;
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
 * @param dateOfBirth guest date of birth
 * @param address guest address
 */
public record GuestCreateRequest(
        @NotBlank(message = "First name is required.") @Size(max = 100) String firstName,
        @NotBlank(message = "Last name is required.") @Size(max = 100) String lastName,
        @Size(max = 255) String email,
        @Size(max = 100) String phone,
        @NotBlank(message = "Nationality is required.") @CanonicalNationality @Size(max = 100) String nationality,
        LocalDate dateOfBirth,
        String address) {}
