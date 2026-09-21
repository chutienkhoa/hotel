package com.example.hotel.dto.common.response;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Exposes client-safe Staff Management profile data.
 *
 * @param id Staff identifier
 * @param staffCode immutable, backend-generated Staff Code
 * @param firstName Staff first name
 * @param lastName Staff last name
 * @param phone Staff phone number, or {@code null} when not supplied
 * @param email Staff email address, or {@code null} when not supplied
 * @param position Staff free-text position, or {@code null} when not supplied
 * @param startDate Staff employment start date
 * @param active {@code true} when the Staff member is currently active
 * @param notes optional Staff notes, or {@code null} when not supplied
 */
public record StaffResponse(
        UUID id,
        String staffCode,
        String firstName,
        String lastName,
        String phone,
        String email,
        String position,
        LocalDate startDate,
        boolean active,
        String notes) {}
