package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.ReservationRoomRevenueRow;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies the Monthly Financial Report calculation rules of spec §61 without a database. */
class MonthlyFinancialReportServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final BigDecimal RATE = new BigDecimal("1000000");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final AdditionalRevenueRepository revenues = mock(AdditionalRevenueRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final com.example.hotel.repository.booking.StayExtensionRoomRepository extensionRooms =
            mock(com.example.hotel.repository.booking.StayExtensionRoomRepository.class);
    private final MonthlyFinancialReportService service =
            new MonthlyFinancialReportService(reservations, extensionRooms, revenues, expenses,
                    java.time.Clock.fixed(java.time.Instant.parse("2027-01-01T00:00:00Z"), java.time.ZoneId.of("Asia/Ho_Chi_Minh")));

    private void rows(ReservationRoomRevenueRow... rows) {
        when(reservations.findRoomRevenueRows(any(), any(), any())).thenReturn(List.of(rows));
    }

    private static ReservationRoomRevenueRow row(String checkIn, String checkOut, String currency) {
        return row(UUID.randomUUID(), checkIn, checkOut, currency, RATE, null);
    }

    private static ReservationRoomRevenueRow row(
            UUID reservationId, String checkIn, String checkOut, String currency, BigDecimal rate, BigDecimal total) {
        LocalDate in = LocalDate.parse(checkIn);
        LocalDate out = LocalDate.parse(checkOut);
        BigDecimal actualTotal = total != null
                ? total
                : rate.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, out)));
        return new ReservationRoomRevenueRow(UUID.randomUUID(), reservationId, in, out, rate, actualTotal, currency,
                ReservationStatus.CHECKED_OUT);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private void extension(String from, String to, String currency, String amountOverride) {
        LocalDate in = LocalDate.parse(from);
        LocalDate out = LocalDate.parse(to);
        BigDecimal amount = amountOverride != null
                ? new BigDecimal(amountOverride)
                : RATE.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, out)));
        when(extensionRooms.findRevenueRows(any(), any(), any())).thenReturn(List.of(
                new com.example.hotel.repository.booking.StayExtensionRevenueRow(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), in, out, RATE, amount, currency, ReservationStatus.CHECKED_OUT)));
    }

    /** Confirms extension revenue is added once on top of the unchanged original room revenue. */
    @Test
    void shouldAddExtensionRevenueOnceToTheOriginalRoomRevenue() {
        rows(row("2026-09-20", "2026-09-22", "VND"));
        extension("2026-09-22", "2026-09-24", "VND", null);

        assertMoney("4000000", service.report(SEPTEMBER).roomRevenue());
    }

    /** Confirms a month with only extension revenue reports it, and non-extended data is unchanged. */
    @Test
    void shouldReportExtensionOnlyMonthAndLeaveNonExtendedDataUnchanged() {
        rows(row("2026-09-10", "2026-09-12", "VND"));
        assertMoney("2000000", service.report(SEPTEMBER).roomRevenue());

        rows();
        extension("2026-09-29", "2026-10-02", "VND", null);
        assertMoney("2000000", service.report(SEPTEMBER).roomRevenue());
        assertMoney("1000000", service.report(OCTOBER).roomRevenue());
    }

    /** Confirms an extension crossing the month boundary is split by night start like ReservationRoom revenue. */
    @Test
    void shouldSplitAnExtensionCrossingTheMonthBoundary() {
        rows();
        extension("2026-09-29", "2026-10-02", "VND", null);

        assertMoney("2000000", service.report(SEPTEMBER).roomRevenue());
        assertMoney("1000000", service.report(OCTOBER).roomRevenue());
    }

    /** Confirms a non-VND extension follows the Reservation currency: excluded and reported in the warning. */
    @Test
    void shouldExcludeNonVndExtensionAndWarn() {
        rows();
        extension("2026-09-22", "2026-09-24", "USD", null);

        MonthlyFinancialReport report = service.report(SEPTEMBER);

        assertMoney("0", report.roomRevenue());
        assertEquals(List.of("USD"), report.nonVndWarning().currencies());
    }

    /** Confirms an extension whose amount differs from rate x nights fails the integrity check. */
    @Test
    void shouldFailIntegrityCheckForAnInconsistentExtension() {
        rows();
        extension("2026-09-22", "2026-09-24", "VND", "1");

        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.hotel.exception.ReportDataIntegrityException.class, () -> service.report(SEPTEMBER));
    }

    /** Confirms the extension query uses the same eligible statuses (CHECKED_IN, CHECKED_OUT only). */
    @Test
    void shouldRequestExtensionsForTheSameEligibleStatuses() {
        rows();

        service.report(SEPTEMBER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ReservationStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(extensionRooms).findRevenueRows(eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1)), statuses.capture());
        assertEquals(List.of(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT), List.copyOf(statuses.getValue()));
    }

    /** Confirms a 30/09 to 03/10 booking gives September one night and October two nights. */
    @Test
    void shouldAllocateCrossMonthRoomRevenueByNightStart() {
        rows(row("2026-09-30", "2026-10-03", "VND"));

        assertMoney("1000000", service.report(SEPTEMBER).roomRevenue());
        assertMoney("2000000", service.report(OCTOBER).roomRevenue());
    }

    /** Confirms an in-month booking and a booking spanning the whole month are allocated in full. */
    @Test
    void shouldAllocateInMonthAndFullMonthBookings() {
        rows(row("2026-09-10", "2026-09-12", "VND"));
        assertMoney("2000000", service.report(SEPTEMBER).roomRevenue());

        rows(row("2026-08-25", "2026-10-05", "VND"));
        assertMoney("30000000", service.report(SEPTEMBER).roomRevenue());
    }

    /** Confirms the query asks only for CHECKED_IN and CHECKED_OUT, so every other status is excluded. */
    @Test
    void shouldRequestOnlyCheckedInAndCheckedOutStatuses() {
        rows();

        service.report(SEPTEMBER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ReservationStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(reservations).findRoomRevenueRows(eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1)), statuses.capture());
        assertEquals(List.of(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT), List.copyOf(statuses.getValue()));
        assertTrue(!statuses.getValue().contains(ReservationStatus.DRAFT));
        assertTrue(!statuses.getValue().contains(ReservationStatus.CONFIRMED));
        assertTrue(!statuses.getValue().contains(ReservationStatus.CANCELLED));
        assertTrue(!statuses.getValue().contains(ReservationStatus.NO_SHOW));
    }

    /** Confirms the month boundaries passed to the query are the inclusive start and exclusive next start. */
    @Test
    void shouldUseHalfOpenMonthBoundaries() {
        rows();

        MonthlyFinancialReport report = service.report(YearMonth.of(2026, 12));

        assertEquals(LocalDate.of(2026, 12, 1), report.monthStart());
        assertEquals(LocalDate.of(2027, 1, 1), report.nextMonthStart());
        assertEquals("VND", report.currency());
    }

    /** Confirms non-VND rows are excluded from money and reported with distinct counts and sorted currencies. */
    @Test
    void shouldExcludeNonVndAndWarnWithDistinctCounts() {
        UUID usdReservation = UUID.randomUUID();
        rows(
                row("2026-09-01", "2026-09-03", "VND"),
                row(usdReservation, "2026-09-05", "2026-09-07", "USD", new BigDecimal("100.00"), null),
                row(usdReservation, "2026-09-05", "2026-09-07", "USD", new BigDecimal("100.00"), null),
                row(UUID.randomUUID(), "2026-09-08", "2026-09-09", "EUR", new BigDecimal("90.00"), null),
                row(UUID.randomUUID(), "2026-09-08", "2026-09-09", "USD", new BigDecimal("80.00"), null));

        MonthlyFinancialReport report = service.report(SEPTEMBER);

        assertMoney("2000000", report.roomRevenue());
        assertMoney("2000000", report.totalRevenue());
        assertMoney("2000000", report.netProfit());
        assertEquals(3, report.nonVndWarning().reservationCount());
        assertEquals(4, report.nonVndWarning().reservationRoomCount());
        assertEquals(List.of("EUR", "USD"), report.nonVndWarning().currencies());
    }

    /** Confirms there is no warning when nothing was excluded. */
    @Test
    void shouldHaveNoWarningWhenAllRowsAreVnd() {
        rows(row("2026-09-01", "2026-09-02", "VND"));

        assertNull(service.report(SEPTEMBER).nonVndWarning());
    }

    /** Confirms a total that differs from rate x nights fails deterministically and is never repaired. */
    @Test
    void shouldFailOnSnapshotMismatch() {
        ReservationRoomRevenueRow bad = row(UUID.randomUUID(), "2026-09-01", "2026-09-03", "VND", RATE, new BigDecimal("1999999"));
        rows(bad);

        ReportDataIntegrityException exception = assertThrows(ReportDataIntegrityException.class, () -> service.report(SEPTEMBER));

        assertEquals(bad.reservationRoomId(), exception.getReservationRoomId());
        assertTrue(exception.getMessage().contains("differs from nightly rate x booked nights"));
    }

    /** Confirms the integrity check also applies to excluded non-VND rows. */
    @Test
    void shouldCheckIntegrityOfNonVndRowsToo() {
        rows(row(UUID.randomUUID(), "2026-09-01", "2026-09-03", "USD", new BigDecimal("100.00"), new BigDecimal("1.00")));

        assertThrows(ReportDataIntegrityException.class, () -> service.report(SEPTEMBER));
    }

    /** Confirms no rows and no records give zero amounts and no margin. */
    @Test
    void shouldReturnZeroWhenNothingRecognized() {
        rows();

        MonthlyFinancialReport report = service.report(SEPTEMBER);

        assertMoney("0", report.roomRevenue());
        assertMoney("0", report.additionalRevenue());
        assertMoney("0", report.expense());
        assertMoney("0", report.totalRevenue());
        assertMoney("0", report.netProfit());
        assertNull(report.profitMargin());
    }

    /** Confirms Additional Revenue uses only RECORDED, and Expense only POSTED, within the month range. */
    @Test
    void shouldRecognizeOnlyRecordedRevenueAndPostedExpense() {
        rows();
        when(revenues.sumAmountByStatusWithin(AdditionalRevenueStatus.RECORDED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(new BigDecimal("500000"));
        when(expenses.sumAmountByStatusWithin(ExpenseStatus.POSTED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .thenReturn(new BigDecimal("200000"));

        MonthlyFinancialReport report = service.report(SEPTEMBER);

        assertMoney("500000", report.additionalRevenue());
        assertMoney("200000", report.expense());
        verify(revenues).sumAmountByStatusWithin(eq(AdditionalRevenueStatus.RECORDED), any(), any());
        verify(expenses).sumAmountByStatusWithin(eq(ExpenseStatus.POSTED), any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(revenues, expenses);
    }

    /** Confirms totals, profit and the margin (2 decimals, half-up) for a positive result. */
    @Test
    void shouldCalculateTotalsProfitAndMargin() {
        rows(row("2026-09-01", "2026-09-04", "VND"));
        when(revenues.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("1000000"));
        when(expenses.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("1000000"));

        MonthlyFinancialReport report = service.report(SEPTEMBER);

        assertMoney("3000000", report.roomRevenue());
        assertMoney("4000000", report.totalRevenue());
        assertMoney("3000000", report.netProfit());
        assertEquals(new BigDecimal("75.00"), report.profitMargin());
    }

    /** Confirms the margin rounds half-up to two decimals. */
    @Test
    void shouldRoundMarginHalfUpToTwoDecimals() {
        rows();
        when(revenues.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("3"));
        when(expenses.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("1"));

        assertEquals(new BigDecimal("66.67"), service.report(SEPTEMBER).profitMargin());
    }

    /** Confirms a loss gives a negative net profit and a negative margin, and expense-only gives no margin. */
    @Test
    void shouldSupportNegativeNetProfit() {
        rows();
        when(revenues.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("1000"));
        when(expenses.sumAmountByStatusWithin(any(), any(), any())).thenReturn(new BigDecimal("1500"));

        MonthlyFinancialReport loss = service.report(SEPTEMBER);
        assertMoney("-500", loss.netProfit());
        assertEquals(new BigDecimal("-50.00"), loss.profitMargin());

        when(revenues.sumAmountByStatusWithin(any(), any(), any())).thenReturn(null);
        MonthlyFinancialReport expenseOnly = service.report(SEPTEMBER);
        assertMoney("-1500", expenseOnly.netProfit());
        assertNull(expenseOnly.profitMargin());
    }

    // ------------------------------------------------------------ recognition of started nights

    private MonthlyFinancialReportService serviceOn(String today) {
        return new MonthlyFinancialReportService(reservations, extensionRooms, revenues, expenses,
                java.time.Clock.fixed(LocalDate.parse(today).atTime(10, 0).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static ReservationRoomRevenueRow rowWithStatus(String in, String out, ReservationStatus status) {
        LocalDate checkIn = LocalDate.parse(in);
        LocalDate checkOut = LocalDate.parse(out);
        BigDecimal total = RATE.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut)));
        return new ReservationRoomRevenueRow(UUID.randomUUID(), UUID.randomUUID(), checkIn, checkOut, RATE, total, "VND", status);
    }

    /** Confirms a CHECKED_IN 20 to 25 stay on 21/09 recognizes only nights 20 and 21. */
    @Test
    void shouldRecognizeOnlyStartedNightsOfACheckedInStay() {
        rows(rowWithStatus("2026-09-20", "2026-09-25", ReservationStatus.CHECKED_IN));

        assertMoney("2000000", serviceOn("2026-09-21").report(SEPTEMBER).roomRevenue());
        assertMoney("3000000", serviceOn("2026-09-22").report(SEPTEMBER).roomRevenue());
        assertMoney("5000000", serviceOn("2026-09-30").report(SEPTEMBER).roomRevenue());
    }

    /** Confirms CHECKED_OUT keeps the contracted nights even when the clock is before the booked end. */
    @Test
    void shouldKeepContractedNightsForCheckedOutStays() {
        rows(rowWithStatus("2026-09-20", "2026-09-25", ReservationStatus.CHECKED_OUT));

        assertMoney("5000000", serviceOn("2026-09-21").report(SEPTEMBER).roomRevenue());
    }

    /** Confirms the month boundary: 30/09 to 03/10 with today 01/10 gives September 1 night, October 1 night. */
    @Test
    void shouldSplitStartedNightsAcrossMonths() {
        rows(rowWithStatus("2026-09-30", "2026-10-03", ReservationStatus.CHECKED_IN));
        MonthlyFinancialReportService service = serviceOn("2026-10-01");

        assertMoney("1000000", service.report(SEPTEMBER).roomRevenue());
        assertMoney("1000000", service.report(OCTOBER).roomRevenue());
    }

    /** Confirms a future month recognizes nothing for a CHECKED_IN stay whose nights have not started. */
    @Test
    void shouldRecognizeNothingForMonthsThatHaveNotStarted() {
        rows(rowWithStatus("2026-10-02", "2026-10-04", ReservationStatus.CHECKED_IN));

        assertMoney("0", serviceOn("2026-09-21").report(OCTOBER).roomRevenue());
    }

    /** Confirms extension lines follow the same started-night cap; elapsed extension nights are recognized. */
    @Test
    void shouldCapExtensionNightsLikeOriginalNights() {
        rows();
        when(extensionRooms.findRevenueRows(any(), any(), any())).thenReturn(List.of(
                new com.example.hotel.repository.booking.StayExtensionRevenueRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-25"), RATE, RATE.multiply(BigDecimal.valueOf(3)), "VND",
                        ReservationStatus.CHECKED_IN)));

        assertMoney("2000000", serviceOn("2026-09-23").report(SEPTEMBER).roomRevenue());
        assertMoney("0", serviceOn("2026-09-21").report(SEPTEMBER).roomRevenue());
        assertMoney("3000000", serviceOn("2026-09-26").report(SEPTEMBER).roomRevenue());
    }
}
