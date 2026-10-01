package com.example.hotel.dto.common.response;

/**
 * Returns the access token issued after successful authentication.
 *
 * @param accessToken the signed JWT access token
 */
public record LoginResponse(String accessToken) {
}
