package com.example.hotel.dto.common.request;

import java.util.UUID;

/**
 * Carries the client-controlled fields for creating a PMS user account. Structural validation of
 * the username, password, and role happens in the service so every rule yields a friendly message.
 *
 * @param staffId optional Staff member to link the account to
 * @param username requested login name
 * @param password requested plaintext password, never persisted or echoed
 * @param confirmPassword password confirmation, never persisted or echoed
 * @param role required role code
 */
public record UserCreateRequest(
        UUID staffId, String username, String password, String confirmPassword, String role) {}
