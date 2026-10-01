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
        String address) {}
