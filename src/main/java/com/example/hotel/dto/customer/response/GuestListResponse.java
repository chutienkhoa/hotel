package com.example.hotel.dto.customer.response;

import java.util.UUID;

/**
 * Provides the compact Guest data required by the paginated Guest Management list.
 *
 * @param id Guest identifier used by the existing detail route
 * @param guestCode immutable Guest-facing code
 * @param firstName Guest first name
 * @param lastName Guest last name
 * @param email Guest email address
 * @param nationality display-ready nationality text and optional flag
 */
public record GuestListResponse(
        UUID id,
        String guestCode,
        String firstName,
        String lastName,
        String email,
        GuestNationalityDisplay nationality) {}
