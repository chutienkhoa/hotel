package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentNightRow;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.room.RoomInventoryPeriodRepository;
import com.example.hotel.repository.room.RoomInventoryPeriodRow;
import com.example.hotel.service.room.RoomInventoryHistoryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates the Monthly Occupancy Report (spec §61). Occupied room-nights come from actual
 * StayRoomAssignment history, one per {@code (originalReservationRoomId, hotel night)}; sellable
 * room-nights and the historical RoomType come from Room inventory history. A night belongs to the hotel
 * date on which it starts, and the final inventory state of a date owns that night. The current month
 * reports completed nights only. Data that cannot be trusted fails the report; nothing is repaired or
 * deduplicated. The result is locale-free.
 */
@Service
public class MonthlyOccupancyReportService {

    private static final int RATE_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final StayRoomAssignmentRepository assignments;
    private final StayRepository stays;
    private final RoomInventoryPeriodRepository inventoryPeriods;
    private final RoomInventoryHistoryService inventoryHistory;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param assignments source of actual occupancy intervals
     * @param stays source of the assignment-coverage validation
     * @param inventoryPeriods source of sellable inventory and historical RoomType
     * @param inventoryHistory source of the first fully supported history month
     * @param clock hotel business clock; its zone defines the hotel date
     */
    public MonthlyOccupancyReportService(
            StayRoomAssignmentRepository assignments,
            StayRepository stays,
            RoomInventoryPeriodRepository inventoryPeriods,
            RoomInventoryHistoryService inventoryHistory,
            Clock clock) {
        this.assignments = assignments;
        this.stays = stays;
        this.inventoryPeriods = inventoryPeriods;
        this.inventoryHistory = inventoryHistory;
        this.clock = clock;
    }

    /**
     * Builds the report for one calendar month.
     *
     * @param month the reported month
     * @return the immutable report result
     * @throws ReportPeriodUnavailableException if the month is in the future or before the first fully supported history month
     * @throws ReportDataIntegrityException if occupancy or inventory history is inconsistent
     */
    @Transactional(readOnly = true)
    public MonthlyOccupancyReport report(YearMonth month) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(today);
        if (month.isAfter(currentMonth)) {
            throw new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "Future month " + month);
        }
        Optional<YearMonth> firstSupported = inventoryHistory.firstFullySupportedMonth();
        if (firstSupported.isPresent() && month.isBefore(firstSupported.get())) {
            throw new ReportPeriodUnavailableException(
                    Reason.HISTORY_UNAVAILABLE,
                    firstSupported.get(),
                    "Month " + month + " is before the first fully supported month " + firstSupported.get());
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);
        LocalDate reportEnd = month.equals(currentMonth) ? today : nextMonthStart;
        if (!reportEnd.isAfter(monthStart)) {
            return new MonthlyOccupancyReport(
                    month, monthStart, nextMonthStart, monthStart, reportEnd, null, 0, 0, null, List.of());
        }
        Instant startInstant = monthStart.atStartOfDay(zone).toInstant();
        Instant endInstant = reportEnd.atStartOfDay(zone).toInstant();

        verifyAssignmentCoverage(startInstant, endInstant);
        Map<NightKey, InventoryNight> inventory = new HashMap<>();
        Map<UUID, TypeTotals> totalsByType = new HashMap<>();
        expandInventory(monthStart, reportEnd, zone, startInstant, endInstant, inventory, totalsByType);
        long occupied = expandOccupancy(monthStart, reportEnd, zone, startInstant, endInstant, inventory, totalsByType);

        long sellable = totalsByType.values().stream().mapToLong(totals -> totals.sellable).sum();
        List<RoomTypeOccupancy> rows = totalsByType.values().stream()
                .sorted(Comparator.comparing(totals -> totals.code))
                .map(totals -> new RoomTypeOccupancy(
                        totals.id, totals.code, totals.name, totals.occupied, totals.sellable,
                        rate(totals.occupied, totals.sellable)))
                .toList();
        return new MonthlyOccupancyReport(
                month, monthStart, nextMonthStart, monthStart, reportEnd, reportEnd.minusDays(1), occupied, sellable,
                rate(occupied, sellable), rows);
    }

    /**
     * Fails when a booked room of a Stay overlapping the period has no assignment history at all.
     *
     * @param startInstant inclusive period start
     * @param endInstant exclusive period end
     */
    private void verifyAssignmentCoverage(Instant startInstant, Instant endInstant) {
        List<UUID> missing = stays.findReservationRoomIdsWithoutAssignmentOverlapping(startInstant, endInstant);
        if (!missing.isEmpty()) {
            throw new ReportDataIntegrityException(
                    missing.get(0),
                    missing.size() + " stay room(s) overlap the report period without StayRoomAssignment history; first "
                            + missing.get(0));
        }
    }

    /**
     * Expands inventory periods to hotel nights, counting sellable nights per historical RoomType.
     *
     * @param reportStart first hotel night included
     * @param reportEnd exclusive end date
     * @param zone hotel time zone
     * @param startInstant inclusive period start
     * @param endInstant exclusive period end
     * @param inventory receives the inventory state of each (Room, night)
     * @param totalsByType receives per-type sellable counts
     */
    private void expandInventory(
            LocalDate reportStart,
            LocalDate reportEnd,
            ZoneId zone,
            Instant startInstant,
            Instant endInstant,
            Map<NightKey, InventoryNight> inventory,
            Map<UUID, TypeTotals> totalsByType) {
        Map<UUID, List<RoomInventoryPeriodRow>> byRoom = new LinkedHashMap<>();
        for (RoomInventoryPeriodRow row : inventoryPeriods.findRowsOverlapping(startInstant, endInstant)) {
            byRoom.computeIfAbsent(row.roomId(), key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<UUID, List<RoomInventoryPeriodRow>> entry : byRoom.entrySet()) {
            verifyChain(entry.getKey(), entry.getValue(), endInstant);
            for (RoomInventoryPeriodRow row : entry.getValue()) {
                TypeTotals totals = totalsByType.computeIfAbsent(
                        row.roomTypeId(), id -> new TypeTotals(id, row.roomTypeCode(), row.roomTypeName()));
                LocalDate from = later(LocalDate.ofInstant(row.effectiveFrom(), zone), reportStart);
                LocalDate to = earlier(
                        row.effectiveTo() == null ? reportEnd : LocalDate.ofInstant(row.effectiveTo(), zone), reportEnd);
                boolean sellable = row.unavailableReason() == null;
                for (LocalDate night = from; night.isBefore(to); night = night.plusDays(1)) {
                    if (inventory.put(new NightKey(row.roomId(), night), new InventoryNight(row.roomTypeId(), sellable)) != null) {
                        throw new ReportDataIntegrityException(
                                "Room " + row.roomId() + " has overlapping inventory coverage on " + night);
                    }
                    if (sellable) {
                        totals.sellable++;
                    }
                }
            }
        }
    }

    /**
     * Verifies one Room's fetched inventory periods form an exact, gap-free chain ending open or beyond the period.
     *
     * @param roomId Room the periods describe
     * @param periods the Room's periods ordered by start
     * @param endInstant exclusive period end
     */
    private void verifyChain(UUID roomId, List<RoomInventoryPeriodRow> periods, Instant endInstant) {
        for (int index = 1; index < periods.size(); index++) {
            Instant previousEnd = periods.get(index - 1).effectiveTo();
            if (previousEnd == null || !previousEnd.equals(periods.get(index).effectiveFrom())) {
                throw new ReportDataIntegrityException(
                        "Room " + roomId + " inventory periods overlap or leave a gap at "
                                + periods.get(index).effectiveFrom());
            }
        }
        Instant lastEnd = periods.get(periods.size() - 1).effectiveTo();
        if (lastEnd != null && lastEnd.isBefore(endInstant)) {
            throw new ReportDataIntegrityException("Room " + roomId + " has no open inventory period after " + lastEnd);
        }
    }

    /**
     * Expands assignments to occupied hotel nights, one per (lineage, night), and attributes each to the
     * inventory state of its physical Room and night.
     *
     * @param reportStart first hotel night included
     * @param reportEnd exclusive end date
     * @param zone hotel time zone
     * @param startInstant inclusive period start
     * @param endInstant exclusive period end
     * @param inventory inventory state of each (Room, night)
     * @param totalsByType receives per-type occupied counts
     * @return total occupied room-nights
     */
    private long expandOccupancy(
            LocalDate reportStart,
            LocalDate reportEnd,
            ZoneId zone,
            Instant startInstant,
            Instant endInstant,
            Map<NightKey, InventoryNight> inventory,
            Map<UUID, TypeTotals> totalsByType) {
        Set<NightKey> lineageNights = new HashSet<>();
        Set<NightKey> roomNights = new HashSet<>();
        long occupied = 0;
        for (StayRoomAssignmentNightRow row : assignments.findNightRowsOverlapping(startInstant, endInstant)) {
            if (row.assignedTo() != null && !row.assignedTo().isAfter(row.assignedFrom())) {
                throw new ReportDataIntegrityException(
                        "Assignment of lineage " + row.lineageId() + " ends at or before it starts");
            }
            LocalDate from = later(LocalDate.ofInstant(row.assignedFrom(), zone), reportStart);
            LocalDate to = earlier(
                    row.assignedTo() == null ? reportEnd : LocalDate.ofInstant(row.assignedTo(), zone), reportEnd);
            for (LocalDate night = from; night.isBefore(to); night = night.plusDays(1)) {
                if (!lineageNights.add(new NightKey(row.lineageId(), night))) {
                    throw new ReportDataIntegrityException(
                            "Lineage " + row.lineageId() + " is occupied more than once on " + night);
                }
                if (!roomNights.add(new NightKey(row.roomId(), night))) {
                    throw new ReportDataIntegrityException(
                            "Room " + row.roomId() + " is occupied more than once on " + night);
                }
                InventoryNight state = inventory.get(new NightKey(row.roomId(), night));
                if (state == null) {
                    throw new ReportDataIntegrityException(
                            "Room " + row.roomId() + " is occupied on " + night + " without inventory history");
                }
                if (!state.sellable()) {
                    throw new ReportDataIntegrityException(
                            "Room " + row.roomId() + " is occupied on " + night + " while not sellable");
                }
                totalsByType.get(state.roomTypeId()).occupied++;
                occupied++;
            }
        }
        return occupied;
    }

    private BigDecimal rate(long occupied, long sellable) {
        if (sellable == 0) {
            return null;
        }
        return BigDecimal.valueOf(occupied)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(sellable), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    private LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    /** Identifies one (entity, hotel night) pair. */
    private record NightKey(UUID id, LocalDate night) {}

    /** Inventory state of one Room on one hotel night. */
    private record InventoryNight(UUID roomTypeId, boolean sellable) {}

    /** Mutable per-RoomType counters used while building the report. */
    private static final class TypeTotals {
        private final UUID id;
        private final String code;
        private final String name;
        private long occupied;
        private long sellable;

        private TypeTotals(UUID id, String code, String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }
    }
}
