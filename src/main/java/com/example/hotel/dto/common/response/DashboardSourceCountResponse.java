package com.example.hotel.dto.common.response;

/** Represents planned Reservation count for one approved booking source in Dashboard Analytics v2. */
public record DashboardSourceCountResponse(String source, long count) {}
