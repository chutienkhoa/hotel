package com.example.hotel.dto.customer.response;

/**
 * Represents one ISO 3166-1 alpha-2 country available for Guest nationality selection.
 *
 * @param code ISO 3166-1 alpha-2 country code
 * @param name canonical English country name
 * @param flag Unicode regional-indicator flag for the country
 */
public record Country(String code, String name, String flag) {}
