package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.dto.common.response.DashboardMonthCountResponse;
import com.example.hotel.dto.common.response.DashboardRoomTypeCountResponse;
import com.example.hotel.dto.common.response.DashboardSourceCountResponse;
import com.example.hotel.dto.common.response.DashboardStatusCountResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import com.example.hotel.repository.room.RoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provides the approved read-only, non-financial Dashboard v1 metrics. */
@Service
public class DashboardService {

    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;
    private final StayRepository stayRepository;
    private final ExpenseRepository expenseRepository;
    private final Clock dashboardClock;

    /**
     * Creates the Dashboard query service with repositories for the approved Dashboard metrics.
     *
     * @param reservationRepository repository for Reservation count queries
     * @param roomRepository repository for active Room count queries
     * @param stayRepository repository for checked-in Stay count queries
     * @param expenseRepository repository for Expense status count queries
     * @param dashboardClock clock configured with the Dashboard business timezone
     */
    public DashboardService(
            ReservationRepository reservationRepository,
            RoomRepository roomRepository,
            StayRepository stayRepository,
            ExpenseRepository expenseRepository,
            Clock dashboardClock) {
        this.reservationRepository = reservationRepository;
        this.roomRepository = roomRepository;
        this.stayRepository = stayRepository;
        this.expenseRepository = expenseRepository;
        this.dashboardClock = dashboardClock;
    }

    /**
     * Loads every approved Dashboard v1 metric without deriving financial or booking-availability data.
     *
     * @return immutable Dashboard v1 data for presentation
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        LocalDate currentDate = LocalDate.now(dashboardClock);
        LocalDate startDate = currentDate.withDayOfYear(1);
        LocalDate endDateExclusive = startDate.plusYears(1);
        List<DashboardStatusCountResponse> roomStatusCounts =
                toStatusCounts(roomRepository.countActiveByStatus());
        Map<String, Long> roomCountsByStatus = roomStatusCounts.stream()
                .collect(Collectors.toMap(
                        DashboardStatusCountResponse::status,
                        DashboardStatusCountResponse::count));

        return new DashboardResponse(
                reservationRepository.count(),
                roomRepository.countByActiveTrue(),
                stayRepository.countByStatus(StayStatus.CHECKED_IN),
                toStatusCounts(reservationRepository.countAllByStatus()),
                roomStatusCounts,
                operationalAlerts(roomCountsByStatus),
                toStatusCounts(expenseRepository.countAllByStatus()),
                completeMonthCounts(
                        currentDate.getYear(),
                        reservationRepository.countByCheckInMonthWithin(startDate, endDateExclusive)),
                toRoomTypeCounts(
                        reservationRepository.countBookedRoomsByRoomTypeWithin(
                                startDate, endDateExclusive)),
                completeSourceCounts(
                        reservationRepository.countBySourceWithin(startDate, endDateExclusive)));
    }

    /**
     * Builds the complete twelve-month calendar-year series, supplying zero for absent query rows.
     *
     * @param year Dashboard reporting year
     * @param rows grouped repository rows containing year, month, and count
     * @return chronological complete monthly count data
     */
    private List<DashboardMonthCountResponse> completeMonthCounts(int year, List<Object[]> rows) {
        Map<Integer, Long> countsByMonth = rows.stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[1]).intValue(),
                        row -> ((Number) row[2]).longValue()));

        return java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(month -> new DashboardMonthCountResponse(
                        year, month, countsByMonth.getOrDefault(month, 0L)))
                .toList();
    }

    /**
     * Converts grouped assigned-room rows into typed RoomType count data.
     *
     * @param rows grouped repository rows containing RoomType code, name, and count
     * @return RoomType count data in repository-defined stable order
     */
    private List<DashboardRoomTypeCountResponse> toRoomTypeCounts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new DashboardRoomTypeCountResponse(
                        (String) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();
    }

    /**
     * Builds a complete approved-source series, supplying zero for absent grouped query rows.
     *
     * @param rows grouped repository rows containing source and count
     * @return source count data in approved source-enum order
     */
    private List<DashboardSourceCountResponse> completeSourceCounts(List<Object[]> rows) {
        Map<BookingSource, Long> countsBySource = rows.stream()
                .collect(Collectors.toMap(
                        row -> (BookingSource) row[0],
                        row -> ((Number) row[1]).longValue()));

        return java.util.Arrays.stream(BookingSource.values())
                .map(source -> new DashboardSourceCountResponse(
                        source.name(), countsBySource.getOrDefault(source, 0L)))
                .toList();
    }

    /**
     * Converts one database status-count result set to immutable presentation data.
     *
     * @param rows grouped status-count result rows
     * @return status counts in repository-defined stable order
     */
    private List<DashboardStatusCountResponse> toStatusCounts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new DashboardStatusCountResponse(
                        ((Enum<?>) row[0]).name(), ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * Creates the approved operational alert list and supplies zero for statuses with no active Rooms.
     *
     * @param roomCountsByStatus active Room counts keyed by operational status
     * @return counts for the approved alert statuses only
     */
    private List<DashboardStatusCountResponse> operationalAlerts(Map<String, Long> roomCountsByStatus) {
        return List.of(
                alert(RoomStatus.DIRTY, roomCountsByStatus),
                alert(RoomStatus.CLEANING, roomCountsByStatus),
                alert(RoomStatus.MAINTENANCE, roomCountsByStatus),
                alert(RoomStatus.OUT_OF_ORDER, roomCountsByStatus));
    }

    /**
     * Builds one operational alert from an active Room status count.
     *
     * @param status approved alert status
     * @param roomCountsByStatus active Room counts keyed by operational status
     * @return alert status and count
     */
    private DashboardStatusCountResponse alert(
            RoomStatus status, Map<String, Long> roomCountsByStatus) {
        return new DashboardStatusCountResponse(status.name(), roomCountsByStatus.getOrDefault(status.name(), 0L));
    }
}
