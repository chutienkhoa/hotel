package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Carries the credentials submitted to authenticate an application user.
 *
 * @param username the user's login name
 * @param password the user's plaintext password
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
