package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import com.example.hotel.entity.room.RoomUnavailableReason;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies occupied and sellable room-night allocation, integrity failures, metrics and month rules (spec §61). */
class MonthlyOccupancyReportServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant LONG_AGO = at(2026, 1, 1, 0, 0);
    private static final Type DOUBLE = new Type(UUID.randomUUID(), "DOUBLE", "Double");
    private static final Type FAMILY = new Type(UUID.randomUUID(), "FAMILY", "Family");

    private final StayRoomAssignmentRepository assignments = mock(StayRoomAssignmentRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final RoomInventoryPeriodRepository periods = mock(RoomInventoryPeriodRepository.class);
    private final RoomInventoryHistoryService history = mock(RoomInventoryHistoryService.class);

    private final List<StayRoomAssignmentNightRow> assignmentRows = new ArrayList<>();
    private final List<RoomInventoryPeriodRow> periodRows = new ArrayList<>();

    private record Type(UUID id, String code, String name) {}

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    /** Builds the service with the hotel clock fixed at 20/10/2026 10:00 (Asia/Ho_Chi_Minh). */
    private MonthlyOccupancyReportService service() {
        return service(at(2026, 10, 20, 10, 0));
    }

    private MonthlyOccupancyReportService service(Instant now) {
        when(assignments.findNightRowsOverlapping(any(), any())).thenReturn(assignmentRows);
        when(periods.findRowsOverlapping(any(), any())).thenReturn(periodRows);
        return new MonthlyOccupancyReportService(assignments, stays, periods, history, Clock.fixed(now, ZONE));
    }

    private UUID sellableRoom(Type type) {
        UUID room = UUID.randomUUID();
        periodRows.add(period(room, type, null, LONG_AGO, null));
        return room;
    }

    private RoomInventoryPeriodRow period(UUID room, Type type, RoomUnavailableReason reason, Instant from, Instant to) {
        return new RoomInventoryPeriodRow(room, type.id(), type.code(), type.name(), reason, from, to);
    }

    private void stay(UUID lineage, UUID room, Instant from, Instant to) {
        assignmentRows.add(new StayRoomAssignmentNightRow(lineage, room, from, to));
    }

    // ----- occupied nights -------------------------------------------------------------------------------

    /** Confirms a simple stay counts the nights it starts, excluding the check-out date. */
    @Test
    void shouldCountSimpleOneRoomStay() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 14, 0), at(2026, 9, 13, 11, 0));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(3, report.occupiedRoomNights());
        assertEquals(30, report.sellableRoomNights());
        assertEquals(new BigDecimal("10.00"), report.occupancyRate());
    }

    /** Confirms a stay crossing the month boundary gives September one night and October two. */
    @Test
    void shouldAllocateCrossMonthStayToTheNightsStartedInEachMonth() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 30, 14, 0), at(2026, 10, 3, 11, 0));

        assertEquals(1, service().report(YearMonth.of(2026, 9)).occupiedRoomNights());
        assertEquals(2, service().report(YearMonth.of(2026, 10)).occupiedRoomNights());
    }

    /** Confirms a same-calendar-day stay contributes no night. */
    @Test
    void shouldCountSameDayStayAsZeroNights() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 10, 0), at(2026, 9, 10, 18, 0));

        assertEquals(0, service().report(YearMonth.of(2026, 9)).occupiedRoomNights());
    }

    /** Confirms a late-night check-in before midnight owns the night of its start date. */
    @Test
    void shouldCountLateNightStayAsTheNightItStarts() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 23, 30), at(2026, 9, 11, 8, 0));

        assertEquals(1, service().report(YearMonth.of(2026, 9)).occupiedRoomNights());
    }

    /** Confirms a Room Change inside one lineage counts each night once (2, not 3). */
    @Test
    void shouldNotInflateOccupancyOnRoomChange() {
        UUID room101 = sellableRoom(DOUBLE);
        UUID room201 = sellableRoom(DOUBLE);
        UUID lineage = UUID.randomUUID();
        stay(lineage, room101, at(2026, 10, 1, 14, 0), at(2026, 10, 2, 16, 0));
        stay(lineage, room201, at(2026, 10, 2, 16, 0), at(2026, 10, 3, 11, 0));

        assertEquals(2, service().report(YearMonth.of(2026, 10)).occupiedRoomNights());
    }

    /** Confirms two Room Changes on one date give the middle room no night and the total stays 2. */
    @Test
    void shouldHandleTwoRoomChangesOnTheSameDate() {
        UUID room101 = sellableRoom(DOUBLE);
        UUID room201 = sellableRoom(DOUBLE);
        UUID room301 = sellableRoom(DOUBLE);
        UUID lineage = UUID.randomUUID();
        stay(lineage, room101, at(2026, 10, 1, 14, 0), at(2026, 10, 2, 10, 0));
        stay(lineage, room201, at(2026, 10, 2, 10, 0), at(2026, 10, 2, 16, 0));
        stay(lineage, room301, at(2026, 10, 2, 16, 0), at(2026, 10, 3, 11, 0));

        assertEquals(2, service().report(YearMonth.of(2026, 10)).occupiedRoomNights());
    }

    /** Confirms a multi-room reservation contributes one night per lineage, not one per stay. */
    @Test
    void shouldCountEachLineageOfAMultiRoomReservation() {
        UUID room101 = sellableRoom(DOUBLE);
        UUID room102 = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room101, at(2026, 9, 10, 14, 0), at(2026, 9, 12, 11, 0));
        stay(UUID.randomUUID(), room102, at(2026, 9, 10, 14, 0), at(2026, 9, 12, 11, 0));

        assertEquals(4, service().report(YearMonth.of(2026, 9)).occupiedRoomNights());
    }

    /** Confirms an open assignment counts only completed nights up to today (exclusive). */
    @Test
    void shouldCountOpenAssignmentOnlyThroughCompletedNights() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 10, 15, 14, 0), null);

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 10));

        assertEquals(5, report.occupiedRoomNights());
    }

    /** Confirms the same lineage occupying the same night twice fails the report. */
    @Test
    void shouldFailOnDuplicateLineageNight() {
        UUID room101 = sellableRoom(DOUBLE);
        UUID room201 = sellableRoom(DOUBLE);
        UUID lineage = UUID.randomUUID();
        stay(lineage, room101, at(2026, 9, 10, 14, 0), at(2026, 9, 13, 11, 0));
        stay(lineage, room201, at(2026, 9, 12, 14, 0), at(2026, 9, 14, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms two lineages in one Room on one night fail the report. */
    @Test
    void shouldFailWhenARoomIsOccupiedTwiceOnANight() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 14, 0), at(2026, 9, 12, 11, 0));
        stay(UUID.randomUUID(), room, at(2026, 9, 11, 14, 0), at(2026, 9, 13, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms an impossible interval (end not after start) fails the report. */
    @Test
    void shouldFailOnImpossibleAssignmentInterval() {
        UUID room = sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 14, 0), at(2026, 9, 10, 14, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms a Stay whose booked room has no assignment history fails the report. */
    @Test
    void shouldFailWhenAStayRoomLacksAssignmentHistory() {
        sellableRoom(DOUBLE);
        when(stays.findReservationRoomIdsWithoutAssignmentOverlapping(any(), any())).thenReturn(List.of(UUID.randomUUID()));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    // ----- inventory --------------------------------------------------------------------------------------

    /** Confirms a sellable period contributes one sellable night per hotel night. */
    @Test
    void shouldCountSellableNights() {
        sellableRoom(DOUBLE);
        sellableRoom(DOUBLE);

        assertEquals(60, service().report(YearMonth.of(2026, 9)).sellableRoomNights());
    }

    /**
     * Confirms MAINTENANCE and OUT_OF_ORDER are excluded and that a mid-day split follows the end-of-date
     * rule: unavailable from 10/09 15:00 removes night 10/09, restored 15/09 10:00 makes night 15/09 sellable.
     */
    @Test
    void shouldExcludeNonSellableNightsUsingEndOfDateRule() {
        for (RoomUnavailableReason reason : RoomUnavailableReason.values()) {
            periodRows.clear();
            UUID room = UUID.randomUUID();
            Instant down = at(2026, 9, 10, 15, 0);
            Instant up = at(2026, 9, 15, 10, 0);
            periodRows.add(period(room, DOUBLE, null, LONG_AGO, down));
            periodRows.add(period(room, DOUBLE, reason, down, up));
            periodRows.add(period(room, DOUBLE, null, up, null));

            MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

            assertEquals(25, report.sellableRoomNights(), reason.name());
        }
    }

    /** Confirms several changes on one date leave only the final state of that date. */
    @Test
    void shouldLetTheFinalStateOfADateOwnItsNight() {
        UUID room = UUID.randomUUID();
        Instant t1 = at(2026, 9, 10, 8, 0);
        Instant t2 = at(2026, 9, 10, 12, 0);
        Instant t3 = at(2026, 9, 10, 18, 0);
        periodRows.add(period(room, DOUBLE, null, LONG_AGO, t1));
        periodRows.add(period(room, DOUBLE, RoomUnavailableReason.OUT_OF_ORDER, t1, t2));
        periodRows.add(period(room, DOUBLE, null, t2, t3));
        periodRows.add(period(room, DOUBLE, RoomUnavailableReason.MAINTENANCE, t3, null));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(9, report.sellableRoomNights());
    }

    /** Confirms a Room created mid-period contributes only the nights it covers. */
    @Test
    void shouldCountOnlyCoveredNightsForARoomCreatedMidPeriod() {
        UUID room = UUID.randomUUID();
        periodRows.add(period(room, DOUBLE, null, at(2026, 9, 16, 15, 0), null));

        assertEquals(15, service().report(YearMonth.of(2026, 9)).sellableRoomNights());
    }

    /** Confirms RoomType comes from the historical period, so a later type change does not rewrite history. */
    @Test
    void shouldAttributeNightsToTheHistoricalRoomType() {
        UUID room = UUID.randomUUID();
        Instant change = at(2026, 9, 21, 15, 0);
        periodRows.add(period(room, DOUBLE, null, LONG_AGO, change));
        periodRows.add(period(room, FAMILY, null, change, null));
        stay(UUID.randomUUID(), room, at(2026, 9, 5, 14, 0), at(2026, 9, 7, 11, 0));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(2, report.roomTypePerformance().size());
        RoomTypeOccupancy doubles = report.roomTypePerformance().get(0);
        RoomTypeOccupancy family = report.roomTypePerformance().get(1);
        assertEquals("DOUBLE", doubles.roomTypeCode());
        assertEquals(20, doubles.sellableRoomNights());
        assertEquals(2, doubles.occupiedRoomNights());
        assertEquals("FAMILY", family.roomTypeCode());
        assertEquals(10, family.sellableRoomNights());
        assertEquals(0, family.occupiedRoomNights());
    }

    /** Confirms overlapping inventory coverage for one Room fails the report. */
    @Test
    void shouldFailOnOverlappingInventoryCoverage() {
        UUID room = UUID.randomUUID();
        periodRows.add(period(room, DOUBLE, null, LONG_AGO, at(2026, 9, 20, 0, 0)));
        periodRows.add(period(room, DOUBLE, null, at(2026, 9, 10, 0, 0), null));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms a gap between a Room's periods, or a chain that ends closed before the period end, fails. */
    @Test
    void shouldFailOnInventoryGapOrMissingOpenPeriod() {
        UUID gapRoom = UUID.randomUUID();
        periodRows.add(period(gapRoom, DOUBLE, null, LONG_AGO, at(2026, 9, 10, 0, 0)));
        periodRows.add(period(gapRoom, DOUBLE, null, at(2026, 9, 12, 0, 0), null));
        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));

        periodRows.clear();
        periodRows.add(period(UUID.randomUUID(), DOUBLE, null, LONG_AGO, at(2026, 9, 10, 0, 0)));
        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms an occupied night with no inventory coverage fails the report. */
    @Test
    void shouldFailWhenAnOccupiedNightHasNoInventoryCoverage() {
        sellableRoom(DOUBLE);
        stay(UUID.randomUUID(), UUID.randomUUID(), at(2026, 9, 10, 14, 0), at(2026, 9, 12, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    /** Confirms an occupied night inside a non-sellable period fails the report. */
    @Test
    void shouldFailWhenAnOccupiedNightIsNotSellable() {
        UUID room = UUID.randomUUID();
        Instant down = at(2026, 9, 9, 10, 0);
        periodRows.add(period(room, DOUBLE, null, LONG_AGO, down));
        periodRows.add(period(room, DOUBLE, RoomUnavailableReason.OUT_OF_ORDER, down, null));
        stay(UUID.randomUUID(), room, at(2026, 9, 10, 14, 0), at(2026, 9, 12, 11, 0));

        assertThrows(ReportDataIntegrityException.class, () -> service().report(YearMonth.of(2026, 9)));
    }

    // ----- calculations -----------------------------------------------------------------------------------

    /** Confirms hotel and RoomType totals and rates, ordered by RoomType code, with hotel rate from totals. */
    @Test
    void shouldCalculateHotelAndRoomTypeMetricsFromTotals() {
        UUID double1 = sellableRoom(DOUBLE);
        UUID double2 = sellableRoom(DOUBLE);
        UUID family = sellableRoom(FAMILY);
        stay(UUID.randomUUID(), double1, at(2026, 9, 1, 14, 0), at(2026, 10, 1, 11, 0));
        stay(UUID.randomUUID(), double2, at(2026, 9, 1, 14, 0), at(2026, 9, 3, 11, 0));
        stay(UUID.randomUUID(), family, at(2026, 9, 1, 14, 0), at(2026, 9, 2, 11, 0));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(33, report.occupiedRoomNights());
        assertEquals(90, report.sellableRoomNights());
        assertEquals(new BigDecimal("36.67"), report.occupancyRate());
        RoomTypeOccupancy doubles = report.roomTypePerformance().get(0);
        RoomTypeOccupancy families = report.roomTypePerformance().get(1);
        assertEquals("DOUBLE", doubles.roomTypeCode());
        assertEquals(32, doubles.occupiedRoomNights());
        assertEquals(60, doubles.sellableRoomNights());
        assertEquals(new BigDecimal("53.33"), doubles.occupancyRate());
        assertEquals(1, families.occupiedRoomNights());
        assertEquals(30, families.sellableRoomNights());
        assertEquals(new BigDecimal("3.33"), families.occupancyRate());
        assertEquals(new BigDecimal("28.33"), doubles.occupancyRate().add(families.occupancyRate()).divide(BigDecimal.valueOf(2)),
                "the average of type rates differs, so the hotel rate must come from totals");
    }

    /** Confirms the rate rounds half up to two decimals: 1 / 32 = 3.125 becomes 3.13. */
    @Test
    void shouldRoundOccupancyRateHalfUpToTwoDecimals() {
        UUID fullMonth = sellableRoom(DOUBLE);
        UUID lastTwoNights = UUID.randomUUID();
        periodRows.add(period(lastTwoNights, DOUBLE, null, at(2026, 9, 29, 6, 0), null));
        stay(UUID.randomUUID(), fullMonth, at(2026, 9, 5, 14, 0), at(2026, 9, 6, 11, 0));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(32, report.sellableRoomNights());
        assertEquals(1, report.occupiedRoomNights());
        assertEquals(new BigDecimal("3.13"), report.occupancyRate());
    }

    /** Confirms zero sellable nights give a null rate (shown as N/A) and no division by zero. */
    @Test
    void shouldReturnNullRateWhenThereAreNoSellableNights() {
        UUID room = UUID.randomUUID();
        Instant down = at(2026, 8, 20, 10, 0);
        periodRows.add(period(room, DOUBLE, null, LONG_AGO, down));
        periodRows.add(period(room, DOUBLE, RoomUnavailableReason.MAINTENANCE, down, null));

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(0, report.sellableRoomNights());
        assertEquals(0, report.occupiedRoomNights());
        assertNull(report.occupancyRate());
        assertEquals(1, report.roomTypePerformance().size());
        assertNull(report.roomTypePerformance().get(0).occupancyRate());
    }

    /** Confirms zero occupied nights give a 0.00 rate when nights are sellable. */
    @Test
    void shouldReturnZeroRateWhenNothingIsOccupied() {
        sellableRoom(DOUBLE);

        assertEquals(new BigDecimal("0.00"), service().report(YearMonth.of(2026, 9)).occupancyRate());
    }

    // ----- month behaviour --------------------------------------------------------------------------------

    /** Confirms a completed month spans the whole month and is not partial. */
    @Test
    void shouldReportCompletedMonthInFull() {
        sellableRoom(DOUBLE);

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 9));

        assertEquals(LocalDate.of(2026, 9, 1), report.reportStart());
        assertEquals(LocalDate.of(2026, 10, 1), report.reportEnd());
        assertEquals(LocalDate.of(2026, 9, 30), report.reportedThrough());
        assertEquals(LocalDate.of(2026, 10, 1), report.nextMonthStart());
        assertEquals(false, report.partialMonth());
    }

    /** Confirms the current month uses completed nights only: today 20/10 means nights through 19/10. */
    @Test
    void shouldUseCompletedNightsOnlyForTheCurrentMonth() {
        sellableRoom(DOUBLE);
        sellableRoom(DOUBLE);

        MonthlyOccupancyReport report = service().report(YearMonth.of(2026, 10));

        assertEquals(LocalDate.of(2026, 10, 20), report.reportEnd());
        assertEquals(LocalDate.of(2026, 10, 19), report.reportedThrough());
        assertEquals(38, report.sellableRoomNights());
        assertTrue(report.partialMonth());
    }

    /** Confirms the first day of the current month has zero completed nights and runs no queries. */
    @Test
    void shouldReportZeroCompletedNightsOnTheFirstDayOfTheMonth() {
        MonthlyOccupancyReport report = service(at(2026, 10, 1, 10, 0)).report(YearMonth.of(2026, 10));

        assertEquals(report.reportStart(), report.reportEnd());
        assertNull(report.reportedThrough());
        assertEquals(0, report.occupiedRoomNights());
        assertEquals(0, report.sellableRoomNights());
        assertNull(report.occupancyRate());
        assertTrue(report.roomTypePerformance().isEmpty());
        verify(assignments, never()).findNightRowsOverlapping(any(), any());
        verify(periods, never()).findRowsOverlapping(any(), any());
    }

    /** Confirms a future month is rejected and nothing is calculated. */
    @Test
    void shouldRejectFutureMonth() {
        ReportPeriodUnavailableException exception = assertThrows(
                ReportPeriodUnavailableException.class, () -> service().report(YearMonth.of(2026, 11)));

        assertEquals(Reason.FUTURE_MONTH, exception.getReason());
        verifyNoInteractions(periods);
    }

    /** Confirms a month before the first fully supported month is rejected without a partial calculation. */
    @Test
    void shouldRejectMonthBeforeFirstFullySupportedMonth() {
        when(history.firstFullySupportedMonth()).thenReturn(Optional.of(YearMonth.of(2026, 10)));

        ReportPeriodUnavailableException exception = assertThrows(
                ReportPeriodUnavailableException.class, () -> service().report(YearMonth.of(2026, 9)));

        assertEquals(Reason.HISTORY_UNAVAILABLE, exception.getReason());
        assertEquals(YearMonth.of(2026, 10), exception.getFirstSupportedMonth());
        verifyNoInteractions(periods);
    }

    /** Confirms the first supported month itself, and later months, are reported. */
    @Test
    void shouldAllowTheFirstSupportedMonthAndLater() {
        sellableRoom(DOUBLE);
        when(history.firstFullySupportedMonth()).thenReturn(Optional.of(YearMonth.of(2026, 9)));

        assertEquals(30, service().report(YearMonth.of(2026, 9)).sellableRoomNights());
        assertEquals(19, service().report(YearMonth.of(2026, 10)).sellableRoomNights());
    }

    /** Confirms no bootstrap boundary is fabricated when the foundation reports none. */
    @Test
    void shouldNotFabricateABoundaryWithoutBootstrapHistory() {
        sellableRoom(DOUBLE);
        when(history.firstFullySupportedMonth()).thenReturn(Optional.empty());

        assertEquals(31, service().report(YearMonth.of(2026, 3)).sellableRoomNights());
    }
}
