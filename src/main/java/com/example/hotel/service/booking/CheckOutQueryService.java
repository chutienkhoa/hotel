package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.CheckOutListItemResponse;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the Check-out operational search/queue and Review screens strictly by reusing
 * existing read-only query services. This service introduces no alternate Reservation/Stay
 * read path and performs no business mutation: the final Check-out state transition remains the
 * sole responsibility of {@link ReservationService#checkOut}.
 */
@Service
public class CheckOutQueryService {

    private static final String READY = "READY";
    private static final String PAYMENT_REQUIRED = "PAYMENT_REQUIRED";

    private final ReservationQueryService reservationQueryService;
    private final StayQueryService stayQueryService;
    private final StayRoomAssignmentQueryService stayRoomAssignmentQueryService;
    private final StayBalanceService stayBalanceService;

    /**
     * Creates the Check-out query service with its read-only collaborators.
     *
     * @param reservationQueryService service reused for Reservation search/pagination and detail
     * @param stayQueryService service used to resolve the Reservation's Stay
     * @param stayRoomAssignmentQueryService service used to read current (open) room assignments
     * @param stayBalanceService service used to calculate the authoritative Outstanding balance
     */
    public CheckOutQueryService(
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            StayRoomAssignmentQueryService stayRoomAssignmentQueryService,
            StayBalanceService stayBalanceService) {
        this.reservationQueryService = reservationQueryService;
        this.stayQueryService = stayQueryService;
        this.stayRoomAssignmentQueryService = stayRoomAssignmentQueryService;
        this.stayBalanceService = stayBalanceService;
    }

    /**
     * Searches CHECKED_IN Reservations only, reusing the existing Reservation search/pagination
     * infrastructure, and enriches each row with the Stay's current rooms and non-financial
     * readiness.
     *
     * @param criteria submitted Reservation Number / Guest / Room filters
     * @param page zero-based requested page number
     * @return a page of CHECKED_IN Reservations matching the supplied filters
     */
    @Transactional(readOnly = true)
    public Page<CheckOutListItemResponse> search(ReservationSearchCriteria criteria, int page) {
        criteria.setStatus(ReservationStatus.CHECKED_IN);
        return reservationQueryService.findPage(criteria, page).map(this::toListItem);
    }

    /**
     * Builds the read-only Check-out Review for one Reservation.
     *
     * @param reservationId Reservation identifier
     * @return the complete Review data
     */
    @Transactional(readOnly = true)
    public CheckOutReviewResponse review(UUID reservationId) {
        ReservationDetailResponse reservation = reservationQueryService.findById(reservationId);
        StayResponse stay = stayQueryService.findByReservationId(reservationId);
        List<CurrentRoomResponse> currentRooms = stayRoomAssignmentQueryService.findCurrentRooms(reservationId);
        String readiness = readiness(stay.id());
        boolean eligible = "CHECKED_IN".equals(reservation.status()) && READY.equals(readiness);
        return new CheckOutReviewResponse(
                reservation.id(),
                reservation.reservationNumber(),
                reservation.status(),
                eligible,
                reservation.guestId(),
                reservation.guestCode(),
                currentRooms,
                reservation.checkInDate(),
                reservation.checkOutDate(),
                stay.actualCheckInAt(),
                readiness);
    }

    /**
     * Converts one searched Reservation summary into its Check-out list row, resolving the
     * Stay's current rooms and readiness.
     *
     * @param reservation matching CHECKED_IN Reservation summary
     * @return the Check-out list row
     */
    private CheckOutListItemResponse toListItem(ReservationSummaryResponse reservation) {
        ReservationDetailResponse detail = reservationQueryService.findById(reservation.id());
        StayResponse stay = stayQueryService.findByReservationId(reservation.id());
        List<CurrentRoomResponse> currentRooms = stayRoomAssignmentQueryService.findCurrentRooms(reservation.id());
        String roomNumbers = currentRooms.stream()
                .map(CurrentRoomResponse::roomNumber)
                .collect(Collectors.joining(", "));
        return new CheckOutListItemResponse(
                reservation.id(),
                reservation.reservationNumber(),
                detail.guestId(),
                detail.guestCode(),
                roomNumbers,
                reservation.checkOutDate(),
                readiness(stay.id()));
    }

    /**
     * Calculates the non-financial readiness indicator from the authoritative Outstanding
     * balance, without exposing any monetary amount.
     *
     * @param stayId Stay identifier
     * @return {@code READY} when Outstanding is exactly zero, otherwise {@code PAYMENT_REQUIRED}
     */
    private String readiness(UUID stayId) {
        StayBalance balance = stayBalanceService.calculate(stayId);
        return balance.outstanding().compareTo(BigDecimal.ZERO) == 0 ? READY : PAYMENT_REQUIRED;
    }
}
