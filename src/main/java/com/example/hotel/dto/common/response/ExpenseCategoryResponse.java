package com.example.hotel.dto.common.response;

import java.util.UUID;

/** Exposes the read-only Expense category fields needed by Expense v1 clients. */
public record ExpenseCategoryResponse(UUID id, String code) {}
