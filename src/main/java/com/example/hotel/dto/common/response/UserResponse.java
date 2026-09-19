package com.example.hotel.dto.common.response;

import java.util.UUID;

/**
 * Exposes client-safe user account data. Never contains a password or password hash.
 *
 * @param id user account identifier
 * @param username immutable login name
 * @param staffId linked Staff identifier, or {@code null} for a standalone account
 * @param staffCode linked Staff Code, or {@code null}
 * @param staffName linked Staff full name, or {@code null}
 * @param staffActive whether the linked Staff member is active; {@code true} when none is linked
 * @param role the account's managed role code, or {@code null} when none is assigned
 * @param active whether the account can currently authenticate
 */
public record UserResponse(
        UUID id,
        String username,
        UUID staffId,
        String staffCode,
        String staffName,
        boolean staffActive,
        String role,
        boolean active) {}
