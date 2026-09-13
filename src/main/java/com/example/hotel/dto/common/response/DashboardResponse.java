package com.example.hotel.dto.common.response;

import java.util.List;

/** Provides the approved read-only Dashboard v1 metrics for Thymeleaf rendering. */
public record DashboardResponse(
        long totalReservations,
        long activeRooms,
        long checkedInStays,
        List<DashboardStatusCountResponse> reservationsByStatus,
        List<DashboardStatusCountResponse> roomsByStatus,
        List<DashboardStatusCountResponse> operationalRoomAlerts,
        List<DashboardStatusCountResponse> expensesByStatus,
        List<DashboardMonthCountResponse> reservationsByCheckInMonth,
        List<DashboardRoomTypeCountResponse> bookedRoomsByRoomType,
        List<DashboardSourceCountResponse> reservationsBySource) {}
