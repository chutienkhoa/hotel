package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.MonthlyExpenseExportRow;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyPaymentExportRow;
import com.example.hotel.dto.common.response.MonthlyReservationExportRow;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.repository.booking.PaymentExportRow;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationExportRow;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.ReservationRoomTypeRow;
import com.example.hotel.repository.common.ExpenseExportRow;
import com.example.hotel.repository.common.ExpenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Monthly Hotel Performance Excel dataset: the unchanged shared summary (which also enforces the
 * selected-month rules) plus the Reservation, Payment and Expense detail rows, each from one narrow query.
 * Presentation, translation and workbook layout belong to the renderer.
 */
@Service
public class MonthlyHotelPerformanceExcelService {

    /** Payments listed in the workbook: cash collected (PAID) and collected then refunded (REFUNDED). */
    static final List<PaymentStatus> PAYMENT_STATUSES = List.of(PaymentStatus.PAID, PaymentStatus.REFUNDED);

    private final MonthlyHotelPerformanceReportService performanceService;
    private final ReservationRepository reservations;
    private final PaymentRepository payments;
    private final ExpenseRepository expenses;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param performanceService builds the shared summary and validates the month
     * @param reservations source of Reservation rows and booked RoomTypes
     * @param payments source of Payment rows
     * @param expenses source of Expense rows
     * @param clock hotel business clock; its zone defines the month's Instant bounds
     */
    public MonthlyHotelPerformanceExcelService(
            MonthlyHotelPerformanceReportService performanceService,
            ReservationRepository reservations,
            PaymentRepository payments,
            ExpenseRepository expenses,
            Clock clock) {
        this.performanceService = performanceService;
        this.reservations = reservations;
        this.payments = payments;
        this.expenses = expenses;
        this.clock = clock;
    }

    /**
     * Builds the dataset for one calendar month.
     *
     * @param month selected month
     * @return the immutable Excel dataset
     * @throws com.example.hotel.exception.ReportPeriodUnavailableException if the month is in the future or before supported history
     * @throws com.example.hotel.exception.ReportDataIntegrityException if used data is inconsistent
     */
    @Transactional(readOnly = true)
    public MonthlyHotelPerformanceExcelData build(YearMonth month) {
        MonthlyHotelPerformanceReport report = performanceService.build(month);
        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);
        Instant startInstant = monthStart.atStartOfDay(clock.getZone()).toInstant();
        Instant endInstant = nextMonthStart.atStartOfDay(clock.getZone()).toInstant();

        Map<UUID, List<String>> roomTypes = new HashMap<>();
        for (ReservationRoomTypeRow row : reservations.findBookedRoomTypesByCheckInWithin(monthStart, nextMonthStart)) {
            roomTypes.computeIfAbsent(row.reservationId(), key -> new ArrayList<>()).add(row.roomTypeName());
        }
        List<MonthlyReservationExportRow> reservationRows = new ArrayList<>();
        for (ReservationExportRow row : reservations.findExportRowsByCheckInWithin(monthStart, nextMonthStart)) {
            reservationRows.add(new MonthlyReservationExportRow(
                    row.reservationNumber(), row.source(), row.otaBookingReference(),
                    guestName(row.guestFirstName(), row.guestLastName()), row.checkInDate(), row.checkOutDate(),
                    List.copyOf(roomTypes.getOrDefault(row.id(), List.of())), row.totalAmount(), row.currency(),
                    row.status()));
        }
        List<MonthlyPaymentExportRow> paymentRows = new ArrayList<>();
        for (PaymentExportRow row : payments.findExportRowsPaidWithin(PAYMENT_STATUSES, startInstant, endInstant)) {
            paymentRows.add(new MonthlyPaymentExportRow(
                    row.paidAt(), row.reservationNumber(), guestName(row.guestFirstName(), row.guestLastName()),
                    row.method(), row.reference(), row.amount(), row.currency(), row.status()));
        }
        List<MonthlyExpenseExportRow> expenseRows = new ArrayList<>();
        for (ExpenseExportRow row : expenses.findExportRowsByStatusWithin(ExpenseStatus.POSTED, monthStart, nextMonthStart)) {
            expenseRows.add(new MonthlyExpenseExportRow(
                    row.expenseDate(), row.categoryName(), row.description(), row.amount(), row.status(),
                    row.createdByUsername()));
        }
        return new MonthlyHotelPerformanceExcelData(
                report, List.copyOf(reservationRows), List.copyOf(paymentRows), List.copyOf(expenseRows));
    }

    private String guestName(String firstName, String lastName) {
        return Stream.of(firstName, lastName)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
    }
}
