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
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
     * infrastructure for Reservation Number and Guest, and enriches each row with the Stay's
     * current rooms and non-financial readiness.
     *
     * <p>The Room filter cannot be delegated to {@link ReservationQueryService}, because its Room
     * filter joins the immutable original {@code ReservationRoom} booking snapshot. After a Room
     * Change, that no longer reflects the Stay's current physical room, so this method instead
     * clears the Room filter before delegating and matches it itself against each row's current
     * open {@code StayRoomAssignment} room numbers. This match applies to the CHECKED_IN
     * Reservations returned on the requested page only, not across the entire filtered result
     * set — an accepted, narrowly-scoped tradeoff for this bounded operational queue (V1 does not
     * introduce a new cross-page search infrastructure here; see Task 26 for general Data Table UX
     * standardization).
     *
     * @param criteria submitted Reservation Number / Guest / Room filters
     * @param page zero-based requested page number
     * @return a page of CHECKED_IN Reservations matching the supplied filters
     */
    @Transactional(readOnly = true)
    public Page<CheckOutListItemResponse> search(ReservationSearchCriteria criteria, int page) {
        criteria.setStatus(ReservationStatus.CHECKED_IN);
        String currentRoomFilter = criteria.getRoom();
        criteria.setRoom(null);
        Page<ReservationSummaryResponse> reservationPage = reservationQueryService.findPage(criteria, page);
        criteria.setRoom(currentRoomFilter);
        List<CheckOutListItemResponse> items =
                reservationPage.getContent().stream().map(this::toListItem).toList();
        if (currentRoomFilter == null || currentRoomFilter.isBlank()) {
            return new PageImpl<>(items, reservationPage.getPageable(), reservationPage.getTotalElements());
        }
        String normalizedFilter = currentRoomFilter.toLowerCase(Locale.ROOT);
        List<CheckOutListItemResponse> matchingCurrentRoom = items.stream()
                .filter(item -> item.currentRoomNumbers().toLowerCase(Locale.ROOT).contains(normalizedFilter))
                .toList();
        return new PageImpl<>(matchingCurrentRoom, reservationPage.getPageable(), matchingCurrentRoom.size());
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
