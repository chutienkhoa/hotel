package com.example.hotel.dto.common.response;

/** Represents one read-only Dashboard count for a lifecycle or operational status. */
public record DashboardStatusCountResponse(String status, long count) {}
