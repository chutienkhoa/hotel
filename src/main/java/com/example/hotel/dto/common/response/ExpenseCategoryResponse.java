package com.example.hotel.dto.common.response;

import java.util.UUID;

/** Exposes the Expense category fields needed by Expense v1 clients. */
public record ExpenseCategoryResponse(UUID id, String code, String name, String description, boolean active) {}
