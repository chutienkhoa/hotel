package com.example.hotel.service.common;

import com.example.hotel.dto.booking.response.FrontDeskArrivalRow;
import com.example.hotel.dto.booking.response.FrontDeskStayRow;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.common.response.DashboardArrivalsKpi;
import com.example.hotel.dto.common.response.DashboardCurrentlyStayingKpi;
import com.example.hotel.dto.common.response.DashboardDeparturesKpi;
import com.example.hotel.dto.common.response.DashboardPermissions;
import com.example.hotel.dto.common.response.DashboardResponse;
import com.example.hotel.dto.common.response.DashboardStatusCountResponse;
import com.example.hotel.dto.common.response.DashboardStayRow;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.service.booking.FrontDeskQueryService;
import com.example.hotel.service.booking.ReservationQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides the approved, operational-first Dashboard V1 read model (Task 33 final Dashboard, spec
 * §9.1): "what is happening at the hotel now/today". It composes existing Front Desk and Reservation
 * read models and business rules; it never reimplements check-in/checkout eligibility, readiness, or
 * financial-amount visibility, and it performs no state change.
 */
@Service
public class DashboardService {

    /**
     * Maximum rows rendered per Dashboard operational table. Each table's KPI count is computed from
     * the full underlying worklist before this limit is applied, so the limit only bounds presentation
     * and never the reported count.
     */
    private static final int DASHBOARD_ROW_LIMIT = 5;

    private final RoomRepository roomRepository;
    private final StayRepository stayRepository;
    private final FrontDeskQueryService frontDeskQueryService;
    private final ReservationQueryService reservationQueryService;
    private final Clock dashboardClock;

    /**
     * Creates the Dashboard query service.
     *
     * @param roomRepository repository for active Room status counts
     * @param stayRepository repository for the in-house guest-headcount query
     * @param frontDeskQueryService canonical Front Desk arrivals/in-house/departures read model
     * @param reservationQueryService canonical Reservation read model, used for Recent Reservations
     * @param dashboardClock clock configured with the Dashboard business timezone
     */
    public DashboardService(
            RoomRepository roomRepository,
            StayRepository stayRepository,
            FrontDeskQueryService frontDeskQueryService,
            ReservationQueryService reservationQueryService,
            Clock dashboardClock) {
        this.roomRepository = roomRepository;
        this.stayRepository = stayRepository;
        this.frontDeskQueryService = frontDeskQueryService;
        this.reservationQueryService = reservationQueryService;
        this.dashboardClock = dashboardClock;
    }

    /**
     * Loads the final Dashboard V1 read model. Each operational block is included only when the
     * supplied permissions allow it; otherwise its KPI is {@code null} and its row list is empty, so the
     * caller can omit the block entirely rather than rendering it disabled.
     *
     * @param permissions the viewer's resolved Dashboard-relevant permissions
     * @return the Dashboard read model for presentation
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(DashboardPermissions permissions) {
        ZoneId hotelZone = dashboardClock.getZone();
        LocalDate hotelToday = LocalDate.now(dashboardClock);

        List<DashboardStatusCountResponse> roomsByStatus =
                completeRoomStatusCounts(roomRepository.countActiveByStatus());
        long totalRooms = roomRepository.countByActiveTrue();
        long availableRooms = statusCount(roomsByStatus, RoomStatus.AVAILABLE);
        long occupiedRooms = statusCount(roomsByStatus, RoomStatus.OCCUPIED);

        DashboardCurrentlyStayingKpi currentlyStaying = null;
        List<DashboardStayRow> currentlyStayingRows = List.of();
        if (permissions.canCheckOut()) {
            Instant now = dashboardClock.instant();
            Instant startOfToday = hotelToday.atStartOfDay(hotelZone).toInstant();
            long guestsNow = stayRepository.sumGuestHeadcountInHouseAt(now);
            long guestsYesterday = stayRepository.sumGuestHeadcountInHouseAt(startOfToday);
            currentlyStaying =
                    new DashboardCurrentlyStayingKpi(guestsNow, occupiedRooms, guestsNow - guestsYesterday);
            currentlyStayingRows = frontDeskQueryService.inHouse().stream()
                    .limit(DASHBOARD_ROW_LIMIT)
                    .map(row -> new DashboardStayRow(row, nightsElapsed(row, hotelToday, hotelZone)))
                    .toList();
        }

        DashboardArrivalsKpi arrivalsKpi = null;
        List<FrontDeskArrivalRow> arrivalRows = List.of();
        if (permissions.canCheckIn()) {
            List<FrontDeskArrivalRow> arrivals = frontDeskQueryService.arrivals();
            List<FrontDeskArrivalRow> todaysArrivals = arrivals.stream()
                    .filter(row -> row.checkInDate().equals(hotelToday))
                    .toList();
            long needsAttentionToday =
                    todaysArrivals.stream().filter(FrontDeskArrivalRow::needsAttention).count();
            arrivalsKpi = new DashboardArrivalsKpi(todaysArrivals.size(), needsAttentionToday);
            arrivalRows = arrivals.stream().limit(DASHBOARD_ROW_LIMIT).toList();
        }

        DashboardDeparturesKpi departuresKpi = null;
        List<DashboardStayRow> departureRows = List.of();
        if (permissions.canCheckOut()) {
            List<FrontDeskStayRow> departures = frontDeskQueryService.departures(permissions.canManagePayment());
            List<FrontDeskStayRow> todaysDepartures = departures.stream()
                    .filter(row -> row.plannedCheckOutDate().equals(hotelToday))
                    .toList();
            long needsAttentionToday =
                    todaysDepartures.stream().filter(FrontDeskStayRow::needsAttention).count();
            departuresKpi = new DashboardDeparturesKpi(todaysDepartures.size(), needsAttentionToday);
            departureRows = departures.stream()
                    .limit(DASHBOARD_ROW_LIMIT)
                    .map(row -> new DashboardStayRow(row, nightsOfStay(row, hotelZone)))
                    .toList();
        }

        List<ReservationSummaryResponse> recentReservations =
                permissions.canViewBooking() ? reservationQueryService.findRecent() : List.of();

        return new DashboardResponse(
                hotelToday,
                currentlyStaying,
                availableRooms,
                arrivalsKpi,
                departuresKpi,
                arrivalRows,
                roomsByStatus,
                totalRooms,
                currentlyStayingRows,
                departureRows,
                recentReservations);
    }

    /**
     * Builds the complete, zero-filled active-Room status series across every {@link RoomStatus} value,
     * so a status with no active Rooms still appears with a count of zero (required for the Room Status
     * donut to always show all 6 categories).
     *
     * @param rows grouped repository rows containing status and count
     * @return status counts for every {@code RoomStatus} value, in enum declaration order
     */
    private List<DashboardStatusCountResponse> completeRoomStatusCounts(List<Object[]> rows) {
        Map<String, Long> countsByStatus = rows.stream()
                .collect(Collectors.toMap(
                        row -> ((Enum<?>) row[0]).name(), row -> ((Number) row[1]).longValue()));
        return Arrays.stream(RoomStatus.values())
                .map(status -> new DashboardStatusCountResponse(
                        status.name(), countsByStatus.getOrDefault(status.name(), 0L)))
                .toList();
    }

    /**
     * Reads one status's count from an already-complete status-count series.
     *
     * @param counts the complete status-count series
     * @param status the status to read
     * @return the matching count, or zero when absent
     */
    private long statusCount(List<DashboardStatusCountResponse> counts, RoomStatus status) {
        return counts.stream()
                .filter(count -> count.status().equals(status.name()))
                .mapToLong(DashboardStatusCountResponse::count)
                .findFirst()
                .orElse(0L);
    }

    /**
     * Computes nights elapsed so far for an in-house Stay: from its actual check-in date to the hotel's
     * current date. This is a presentation-only value, never persisted.
     *
     * @param row the in-house stay row
     * @param hotelToday the hotel's current date
     * @param hotelZone the Dashboard business timezone
     * @return nights elapsed, never negative
     */
    private long nightsElapsed(FrontDeskStayRow row, LocalDate hotelToday, ZoneId hotelZone) {
        LocalDate actualCheckInDate = row.actualCheckInAt().atZone(hotelZone).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(actualCheckInDate, hotelToday));
    }

    /**
     * Computes the full length of stay for a departing Stay: from its actual check-in date to its
     * planned check-out date. This is a presentation-only value, never persisted.
     *
     * @param row the departing stay row
     * @param hotelZone the Dashboard business timezone
     * @return nights of stay, never negative
     */
    private long nightsOfStay(FrontDeskStayRow row, ZoneId hotelZone) {
        LocalDate actualCheckInDate = row.actualCheckInAt().atZone(hotelZone).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(actualCheckInDate, row.plannedCheckOutDate()));
    }
}
