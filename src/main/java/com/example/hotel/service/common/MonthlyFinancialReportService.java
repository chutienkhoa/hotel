package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.NonVndRoomRevenueWarning;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.AdditionalRevenueStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.ReservationRoomRevenueRow;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates the Monthly Financial Report (spec §61): recognized VND Room Revenue allocated by booked
 * calendar room-night, recognized Additional Revenue and Expense, and the derived totals. It returns a
 * locale-free result and knows nothing about presentation. Payments are never used.
 */
@Service
public class MonthlyFinancialReportService {

    static final String REPORT_CURRENCY = "VND";

    /** Reservation statuses whose booked room pricing counts as recognized Room Revenue. */
    static final List<ReservationStatus> ELIGIBLE_STATUSES =
            List.of(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT);

    private static final int MARGIN_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ReservationRepository reservationRepository;
    private final AdditionalRevenueRepository additionalRevenueRepository;
    private final ExpenseRepository expenseRepository;

    /**
     * Creates the service.
     *
     * @param reservationRepository source of ReservationRoom pricing snapshots
     * @param additionalRevenueRepository source of recognized Additional Revenue
     * @param expenseRepository source of recognized Expense
     */
    public MonthlyFinancialReportService(
            ReservationRepository reservationRepository,
            AdditionalRevenueRepository additionalRevenueRepository,
            ExpenseRepository expenseRepository) {
        this.reservationRepository = reservationRepository;
        this.additionalRevenueRepository = additionalRevenueRepository;
        this.expenseRepository = expenseRepository;
    }

    /**
     * Builds the report for one calendar month.
     *
     * @param month the reported month
     * @return the immutable report result
     * @throws ReportDataIntegrityException if a used ReservationRoom total differs from nightly rate x booked nights
     */
    @Transactional(readOnly = true)
    public MonthlyFinancialReport report(YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate nextMonthStart = month.plusMonths(1).atDay(1);

        BigDecimal roomRevenue = BigDecimal.ZERO;
        Set<java.util.UUID> excludedReservations = new HashSet<>();
        int excludedRooms = 0;
        Set<String> excludedCurrencies = new TreeSet<>();

        for (ReservationRoomRevenueRow row :
                reservationRepository.findRoomRevenueRows(monthStart, nextMonthStart, ELIGIBLE_STATUSES)) {
            verifySnapshot(row);
            if (!REPORT_CURRENCY.equals(row.currency())) {
                excludedReservations.add(row.reservationId());
                excludedRooms++;
                excludedCurrencies.add(row.currency());
                continue;
            }
            LocalDate overlapStart = row.checkInDate().isAfter(monthStart) ? row.checkInDate() : monthStart;
            LocalDate overlapEnd = row.checkOutDate().isBefore(nextMonthStart) ? row.checkOutDate() : nextMonthStart;
            long nightsInsideMonth = ChronoUnit.DAYS.between(overlapStart, overlapEnd);
            roomRevenue = roomRevenue.add(row.nightlyRate().multiply(BigDecimal.valueOf(nightsInsideMonth)));
        }

        BigDecimal additionalRevenue = zeroIfNull(additionalRevenueRepository.sumAmountByStatusWithin(
                AdditionalRevenueStatus.RECORDED, monthStart, nextMonthStart));
        BigDecimal expense = zeroIfNull(
                expenseRepository.sumAmountByStatusWithin(ExpenseStatus.POSTED, monthStart, nextMonthStart));
        BigDecimal totalRevenue = roomRevenue.add(additionalRevenue);
        BigDecimal netProfit = totalRevenue.subtract(expense);
        BigDecimal profitMargin = totalRevenue.signum() > 0
                ? netProfit.multiply(HUNDRED).divide(totalRevenue, MARGIN_SCALE, RoundingMode.HALF_UP)
                : null;
        NonVndRoomRevenueWarning warning = excludedRooms == 0
                ? null
                : new NonVndRoomRevenueWarning(excludedReservations.size(), excludedRooms, List.copyOf(excludedCurrencies));

        return new MonthlyFinancialReport(
                month, monthStart, nextMonthStart, REPORT_CURRENCY, roomRevenue, additionalRevenue, totalRevenue,
                expense, netProfit, profitMargin, warning);
    }

    /**
     * Enforces the domain invariant {@code totalAmount = nightlyRate x booked nights}. A mismatch is a
     * data-integrity failure: it is never repaired, proportionally allocated, or ignored.
     *
     * @param row the snapshot to check
     */
    private void verifySnapshot(ReservationRoomRevenueRow row) {
        long bookedNights = ChronoUnit.DAYS.between(row.checkInDate(), row.checkOutDate());
        BigDecimal expectedTotal = row.nightlyRate().multiply(BigDecimal.valueOf(bookedNights));
        if (expectedTotal.compareTo(row.totalAmount()) != 0) {
            throw new ReportDataIntegrityException(
                    row.reservationRoomId(),
                    "ReservationRoom " + row.reservationRoomId() + " total " + row.totalAmount()
                            + " differs from nightly rate x booked nights " + expectedTotal);
        }
    }

    private BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
