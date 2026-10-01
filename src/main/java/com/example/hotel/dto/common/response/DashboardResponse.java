package com.example.hotel.dto.common.response;

import java.util.List;

/** Provides the approved read-only Dashboard v1 metrics for Thymeleaf rendering. */
public record DashboardResponse(
        long reservationsThisYear,
        long reservationsThisMonth,
        String currentMonthLabel,
        long activeRooms,
        long availableRooms,
        long checkedInStays,
        long checkOutTodayStays,
        List<DashboardStatusCountResponse> reservationsByStatus,
        List<DashboardStatusCountResponse> roomsByStatus,
        List<DashboardStatusCountResponse> operationalRoomAlerts,
        List<DashboardStatusCountResponse> expensesByStatus,
        List<DashboardMonthCountResponse> reservationsByCheckInMonth,
        List<DashboardRoomTypeCountResponse> bookedRoomsByRoomType,
        List<DashboardSourceCountResponse> reservationsBySource) {}
