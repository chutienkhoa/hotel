package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.exception.ReportPeriodUnavailableException.Reason;
import com.example.hotel.repository.booking.PaymentExportRow;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationExportRow;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.ReservationRoomTypeRow;
import com.example.hotel.repository.common.ExpenseExportRow;
import com.example.hotel.repository.common.ExpenseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the Excel dataset composes the shared summary and maps the three detail queries without recalculation. */
class MonthlyHotelPerformanceExcelServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final MonthlyHotelPerformanceReportService performance = mock(MonthlyHotelPerformanceReportService.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final MonthlyHotelPerformanceExcelService service = new MonthlyHotelPerformanceExcelService(
            performance, reservations, payments, expenses,
            Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));

    private MonthlyHotelPerformanceReport stubSummary() {
        MonthlyHotelPerformanceReport report = new MonthlyHotelPerformanceReport(
                SEPTEMBER, LocalDate.of(2026, 9, 30), null, null, null, List.of(), 2, List.of(), List.of());
        when(performance.build(SEPTEMBER)).thenReturn(report);
        return report;
    }

    /** Confirms the shared summary is reused as is, and one row per Reservation carries distinct room types in code order. */
    @Test
    void shouldBuildOneRowPerReservationWithDistinctRoomTypes() {
        MonthlyHotelPerformanceReport report = stubSummary();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(reservations.findExportRowsByCheckInWithin(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))).thenReturn(List.of(
                new ReservationExportRow(first, "RSV-1", BookingSource.AGODA, "AGD-1", "Nguyen", "Van A",
                        LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 4), new BigDecimal("100"), "USD", ReservationStatus.CANCELLED),
                new ReservationExportRow(second, "RSV-2", BookingSource.DIRECT, null, " Tanaka ", null,
                        LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6), new BigDecimal("50"), "VND", ReservationStatus.DRAFT)));
        when(reservations.findBookedRoomTypesByCheckInWithin(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))).thenReturn(List.of(
                new ReservationRoomTypeRow(first, "DOUBLE", "Double"), new ReservationRoomTypeRow(first, "TWIN", "Twin")));

        MonthlyHotelPerformanceExcelData data = service.build(SEPTEMBER);

        assertSame(report, data.report());
        assertEquals(2, data.reservations().size());
        assertEquals(List.of("Double", "Twin"), data.reservations().get(0).roomTypeNames());
        assertEquals("Nguyen Van A", data.reservations().get(0).guestName());
        assertEquals("USD", data.reservations().get(0).currency());
        assertEquals(List.of(), data.reservations().get(1).roomTypeNames());
        assertEquals("Tanaka", data.reservations().get(1).guestName());
        assertEquals(report.reservationCount(), data.reservations().size(), "one row per Reservation equals the Summary count");
    }

    /** Confirms Payments use PAID and REFUNDED with Instant bounds built in the hotel time zone. */
    @Test
    void shouldQueryPaymentsForPaidAndRefundedWithHotelZoneBounds() {
        stubSummary();
        when(payments.findExportRowsPaidWithin(any(), any(), any())).thenReturn(List.of(
                new PaymentExportRow(Instant.parse("2026-09-08T17:30:00Z"), "RSV-1", "Maria", "Santos", PaymentMethod.CASH,
                        "Front Desk", new BigDecimal("100.50"), PaymentCurrency.USD, PaymentStatus.REFUNDED)));

        MonthlyHotelPerformanceExcelData data = service.build(SEPTEMBER);

        verify(payments).findExportRowsPaidWithin(
                List.of(PaymentStatus.PAID, PaymentStatus.REFUNDED),
                Instant.parse("2026-08-31T17:00:00Z"),
                Instant.parse("2026-09-30T17:00:00Z"));
        assertEquals(1, data.payments().size());
        assertEquals("Maria Santos", data.payments().get(0).guestName());
        assertEquals(new BigDecimal("100.50"), data.payments().get(0).amount());
        assertEquals(PaymentCurrency.USD, data.payments().get(0).currency());
        assertEquals(PaymentStatus.REFUNDED, data.payments().get(0).status());
    }

    /** Confirms Expenses reuse the Task 29 population: POSTED within the month. */
    @Test
    void shouldQueryPostedExpensesOfTheMonth() {
        stubSummary();
        when(expenses.findExportRowsByStatusWithin(any(), any(), any())).thenReturn(List.of(
                new ExpenseExportRow(LocalDate.of(2026, 9, 3), "Utilities", "Electricity", new BigDecimal("85000"),
                        ExpenseStatus.POSTED, "admin")));

        MonthlyHotelPerformanceExcelData data = service.build(SEPTEMBER);

        verify(expenses).findExportRowsByStatusWithin(ExpenseStatus.POSTED, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
        assertEquals("admin", data.expenses().get(0).createdBy());
        assertEquals("Utilities", data.expenses().get(0).categoryName());
    }

    /** Confirms an unsupported or future month fails before any detail query runs. */
    @Test
    void shouldRejectBeforeQueryingDetails() {
        when(performance.build(SEPTEMBER)).thenThrow(new ReportPeriodUnavailableException(Reason.FUTURE_MONTH, null, "future"));

        assertThrows(ReportPeriodUnavailableException.class, () -> service.build(SEPTEMBER));

        verifyNoInteractions(reservations, payments, expenses);
    }

    /** Confirms zero rows everywhere give empty lists, not fabricated rows. */
    @Test
    void shouldReturnEmptyDetailListsForAnEmptyMonth() {
        stubSummary();

        MonthlyHotelPerformanceExcelData data = service.build(SEPTEMBER);

        assertEquals(0, data.reservations().size());
        assertEquals(0, data.payments().size());
        assertEquals(0, data.expenses().size());
    }
}
