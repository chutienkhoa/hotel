package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ReservationDetailEligibility;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.repository.booking.StayRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports, for Reservation Detail, whether the date-dependent lifecycle actions would currently be accepted. It reuses
 * the exact rules the mutating operations enforce ({@link ArrivalReadinessRules#reservationBlockers} for Check-in and
 * {@link ReservationActionRules} for no-show) with the hotel business clock, and performs no mutation.
 */
@Service
public class ReservationDetailEligibilityService {

    private final StayRepository stays;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param stays repository used to learn whether a Stay already exists
     * @param clock hotel business clock
     */
    public ReservationDetailEligibilityService(StayRepository stays, Clock clock) {
        this.stays = stays;
        this.clock = clock;
    }

    /**
     * Evaluates the Check-in and no-show eligibility of one Reservation.
     *
     * @param reservationId Reservation identifier
     * @param status Reservation status
     * @param checkInDate planned check-in date
     * @return the eligibility flags
     */
    @Transactional(readOnly = true)
    public ReservationDetailEligibility evaluate(UUID reservationId, ReservationStatus status, LocalDate checkInDate) {
        LocalDate today = LocalDate.now(clock);
        boolean checkIn = ArrivalReadinessRules
                .reservationBlockers(status, checkInDate, today, () -> stays.existsByReservationId(reservationId))
                .isEmpty();
        return new ReservationDetailEligibility(
                checkIn, ReservationActionRules.noShowEligible(status, checkInDate, today));
    }
}
