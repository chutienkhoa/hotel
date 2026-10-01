package com.example.hotel.dto.common.request;

import java.util.UUID;

/**
 * Carries the editable fields of a PMS user account. The username is immutable and never accepted.
 *
 * @param staffId optional Staff member to link the account to
 * @param role required role code
 */
public record UserUpdateRequest(UUID staffId, String role) {}
