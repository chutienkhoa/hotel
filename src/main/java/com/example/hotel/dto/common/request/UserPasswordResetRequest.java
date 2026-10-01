package com.example.hotel.dto.common.request;

/**
 * Carries an administrator-supplied replacement password.
 *
 * @param newPassword replacement plaintext password, never persisted or echoed
 * @param confirmPassword confirmation of the replacement password, never persisted or echoed
 */
public record UserPasswordResetRequest(String newPassword, String confirmPassword) {}
