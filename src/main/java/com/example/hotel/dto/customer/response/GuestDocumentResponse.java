package com.example.hotel.dto.customer.response;

import java.util.UUID;

/**
 * Exposes only safe Guest document metadata for server-rendered Guest pages.
 *
 * @param id document identifier, used to build its secure View/Remove routes
 * @param originalName original upload filename retained only as display metadata
 */
public record GuestDocumentResponse(UUID id, String originalName) {}
