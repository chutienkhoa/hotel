package com.example.hotel.dto.common.response;

import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import java.time.LocalDate;
import java.util.List;

/**
 * Provides the approved, operational-first Dashboard V1 metrics for Thymeleaf rendering (Task 33 final
 * Dashboard, spec §9.1). Every operational block is independently permission-gated by the controller:
 * when the viewer lacks the block's canonical permission, its row list is empty and its KPI record is
 * {@code null}, so the template omits the block entirely rather than rendering it disabled.
 *
 * @param hotelToday the hotel's current date, for template logic shared with Front Desk fragments
 * @param currentlyStaying Currently Staying KPI, or {@code null} without {@code PERM_CHECK_OUT}
 * @param availableRooms current available-room count; always present ({@code PERM_VIEW_REPORT} only)
 * @param arrivalsKpi Today's Arrivals KPI, or {@code null} without {@code PERM_CHECK_IN}
 * @param departuresKpi Today's Departures KPI, or {@code null} without {@code PERM_CHECK_OUT}
 * @param arrivalRows Today's Arrivals worklist rows (capped for presentation), empty without {@code
 *     PERM_CHECK_IN}
 * @param roomsByStatus current active Room counts for every {@code RoomStatus} value, zero-filled;
 *     always present
 * @param totalRooms total active Room count (the sum of {@code roomsByStatus}); always present
 * @param currentlyStayingRows Currently Staying worklist rows (capped for presentation), empty without
 *     {@code PERM_CHECK_OUT}
 * @param departureRows Today's Departures worklist rows (capped for presentation), empty without
 *     {@code PERM_CHECK_OUT}
 * @param recentReservations the 5 most recently created Reservations, empty without {@code
 *     PERM_VIEW_BOOKING}
 */
public record DashboardResponse(
        LocalDate hotelToday,
        DashboardCurrentlyStayingKpi currentlyStaying,
        long availableRooms,
        DashboardArrivalsKpi arrivalsKpi,
        DashboardDeparturesKpi departuresKpi,
        List<FrontDeskArrivalRow> arrivalRows,
        List<DashboardStatusCountResponse> roomsByStatus,
        long totalRooms,
        List<DashboardStayRow> currentlyStayingRows,
        List<DashboardStayRow> departureRows,
        List<ReservationSummaryResponse> recentReservations) {}
