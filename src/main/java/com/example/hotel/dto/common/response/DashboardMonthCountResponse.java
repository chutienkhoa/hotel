package com.example.hotel.dto.common.response;

/** Represents one planned check-in month count for Dashboard Analytics v2. */
public record DashboardMonthCountResponse(int year, int month, long count) {}
