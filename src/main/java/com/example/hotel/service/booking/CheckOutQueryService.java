package com.example.hotel.service.booking;

import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.CheckOutCompleteResponse;
import com.example.hotel.dto.booking.response.CheckOutFinancialSummary;
import com.example.hotel.dto.booking.response.CheckOutListItemResponse;
import com.example.hotel.dto.booking.response.CheckOutReviewResponse;
import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.dto.booking.response.ReservationActivityEntry;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the Check-out operational search/queue and Review screens strictly by reusing
 * existing read-only query services. This service introduces no alternate Reservation/Stay
 * read path and performs no business mutation: the final Check-out state transition remains the
 * sole responsibility of {@link ReservationService#checkOut}.
 */
@Service
public class CheckOutQueryService {

    private static final String READY = "READY";

    private final ReservationQueryService reservationQueryService;
    private final StayQueryService stayQueryService;
    private final StayRoomAssignmentQueryService stayRoomAssignmentQueryService;
    private final StayBalanceService stayBalanceService;
    private final com.example.hotel.service.room.RoomQueryService roomQueryService;
    private final ChargeService chargeService;
    private final PaymentService paymentService;
    private final ReservationActivityQueryService reservationActivityQueryService;
    private final java.time.Clock clock;

    /**
     * Creates the Check-out query service with its read-only collaborators.
     *
     * @param reservationQueryService service reused for Reservation search/pagination and detail
     * @param stayQueryService service used to resolve the Reservation's Stay
     * @param stayRoomAssignmentQueryService service used to read current (open) room assignments
     * @param stayBalanceService service used to calculate the authoritative Outstanding balance
     * @param roomQueryService service used to resolve the current rooms' Room Type
     * @param chargeService service used to list the Stay's Charges
     * @param paymentService service used to list the Stay's Payments
     * @param reservationActivityQueryService service used to read the actor of the CHECK_OUT audit entry
     * @param clock hotel business clock
     */
    public CheckOutQueryService(
            ReservationQueryService reservationQueryService,
            StayQueryService stayQueryService,
            StayRoomAssignmentQueryService stayRoomAssignmentQueryService,
            StayBalanceService stayBalanceService,
            com.example.hotel.service.room.RoomQueryService roomQueryService,
            ChargeService chargeService,
            PaymentService paymentService,
            ReservationActivityQueryService reservationActivityQueryService,
            java.time.Clock clock) {
        this.reservationQueryService = reservationQueryService;
        this.stayQueryService = stayQueryService;
        this.stayRoomAssignmentQueryService = stayRoomAssignmentQueryService;
        this.stayBalanceService = stayBalanceService;
        this.roomQueryService = roomQueryService;
        this.chargeService = chargeService;
        this.paymentService = paymentService;
        this.reservationActivityQueryService = reservationActivityQueryService;
        this.clock = clock;
    }


    /**
     * Searches CHECKED_IN Reservations and enriches each row with current rooms and non-financial
     * readiness.
     *
     * <p>The Room filter refers to the Stay's CURRENT room. It is applied by the shared Reservation
     * query as a database predicate over the open {@code StayRoomAssignment} rows (not the
     * immutable original {@code ReservationRoom} snapshot), so it filters and counts before
     * pagination and stays correct after a Room Change and for multi-room Stays.</p>
     *
     * @param criteria submitted Reservation Number / Guest / Room filters
     * @param page zero-based requested page number
     * @return a page of CHECKED_IN Reservations matching the supplied filters
     */
    @Transactional(readOnly = true)
    public Page<CheckOutListItemResponse> search(ReservationSearchCriteria criteria, int page) {
        criteria.setStatus(ReservationStatus.CHECKED_IN);
        if (TableSorts.CHECK_OUT.key(criteria.getSort(), criteria.getDir()) == null) {
            criteria.setSort(null);
            criteria.setDir(null);
        }
        String currentRoomFilter = criteria.getRoom();
        criteria.setRoom(null);
        criteria.setCurrentRoom(currentRoomFilter);
        Page<ReservationSummaryResponse> reservationPage;
        try {
            reservationPage = reservationQueryService.findPage(criteria, page);
        } finally {
            criteria.setCurrentRoom(null);
            criteria.setRoom(currentRoomFilter);
        }
        return reservationPage.map(this::toListItem);
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
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        long overdueDays = OverdueDeparture.overdueDays(reservation.checkOutDate(), today);
        // Eligibility composes the financial readiness with the (separate) overdue rule.
        boolean eligible = "CHECKED_IN".equals(reservation.status()) && READY.equals(readiness) && overdueDays == 0;
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
                readiness,
                overdueDays,
                today,
                reservation.source(),
                reservation.otaBookingReference(),
                reservation.adultCount(),
                reservation.childCount(),
                reservation.reservedAt(),
                roomTypeLabel(currentRooms));
    }

    /**
     * Builds the read-only financial context of the Check-out Review from the same authoritative sources as the
     * Folio: totals from {@link StayBalanceService}, rows from the Charge and Payment services. The caller must
     * only request it for a viewer holding {@code MANAGE_PAYMENT}; this method adds no authorization of its own.
     *
     * @param reservationId Reservation identifier
     * @return the totals and rows for the Reservation's Stay
     */
    @Transactional(readOnly = true)
    public CheckOutFinancialSummary financialSummary(UUID reservationId) {
        ReservationDetailResponse reservation = reservationQueryService.findById(reservationId);
        StayResponse stay = stayQueryService.findByReservationId(reservationId);
        StayBalance balance = stayBalanceService.calculate(stay.id());
        // A CHECKED_OUT Stay has no open assignment left: its rates come from the rooms released at checkout.
        java.util.Map<UUID, java.math.BigDecimal> roomRates = ReservationStatus.CHECKED_OUT.name().equals(reservation.status())
                ? stayRoomAssignmentQueryService.findFinalRoomRates(reservationId)
                : stayRoomAssignmentQueryService.findCurrentRoomRates(reservationId);
        return new CheckOutFinancialSummary(
                balance.totalCharges(),
                balance.totalPaidPayments(),
                balance.outstanding(),
                reservation.currency(),
                chargeService.findByStayId(stay.id()),
                paymentService.findByStayId(stay.id()),
                roomRates);
    }

    /**
     * Builds the read-only Checkout Complete screen of a CHECKED_OUT Reservation. Nothing is mutated and nothing is
     * recalculated: status, Stay times, released rooms and the CHECK_OUT actor are read from their existing sources.
     *
     * @param reservationId Reservation identifier
     * @return the Checkout Complete data
     * @throws ResponseStatusException {@code CONFLICT} when the Reservation is not CHECKED_OUT
     */
    @Transactional(readOnly = true)
    public CheckOutCompleteResponse complete(UUID reservationId) {
        ReservationDetailResponse reservation = reservationQueryService.findById(reservationId);
        if (!ReservationStatus.CHECKED_OUT.name().equals(reservation.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is not checked out");
        }
        StayResponse stay = stayQueryService.findByReservationId(reservationId);
        List<CurrentRoomResponse> finalRooms = stayRoomAssignmentQueryService.findFinalRooms(reservationId);
        java.util.Map<UUID, com.example.hotel.dto.room.response.RoomResponse> roomsById = roomQueryService
                .findAllByIds(finalRooms.stream().map(CurrentRoomResponse::roomId).toList()).stream()
                .collect(Collectors.toMap(com.example.hotel.dto.room.response.RoomResponse::id, room -> room));
        List<CheckOutCompleteResponse.CheckedOutRoom> rooms = finalRooms.stream()
                .map(room -> {
                    com.example.hotel.dto.room.response.RoomResponse detail = roomsById.get(room.roomId());
                    return new CheckOutCompleteResponse.CheckedOutRoom(
                            room.roomId(),
                            room.roomNumber(),
                            detail == null || detail.roomType() == null ? null : detail.roomType().name(),
                            detail == null ? null : detail.status());
                })
                .toList();
        String checkedOutBy = reservationActivityQueryService.findByReservationId(reservationId).stream()
                .filter(entry -> "CHECK_OUT".equals(entry.action()))
                .reduce((first, second) -> second)
                .map(ReservationActivityEntry::actorDisplay)
                .filter(actor -> !"—".equals(actor))
                .orElse(null);
        return new CheckOutCompleteResponse(
                reservation.id(),
                reservation.reservationNumber(),
                reservation.status(),
                stay.status(),
                reservation.guestId(),
                reservation.guestCode(),
                rooms,
                reservation.checkInDate(),
                reservation.checkOutDate(),
                stay.actualCheckInAt(),
                stay.actualCheckOutAt(),
                checkedOutBy,
                reservation.adultCount(),
                reservation.childCount());
    }

    /**
     * Joins the distinct Room Types of the Stay's CURRENT rooms, so a Room Change is reflected.
     *
     * @param currentRooms rooms currently occupied by the Stay
     * @return comma-separated Room Type names, or {@code null} when there is no current room
     */
    private String roomTypeLabel(List<CurrentRoomResponse> currentRooms) {
        if (currentRooms.isEmpty()) {
            return null;
        }
        return roomQueryService.findAllByIds(currentRooms.stream().map(CurrentRoomResponse::roomId).toList()).stream()
                .map(room -> room.roomType().name())
                .distinct()
                .collect(Collectors.joining(", "));
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
                readiness(stay.id()),
                OverdueDeparture.overdueDays(reservation.checkOutDate(), java.time.LocalDate.now(clock)));
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
        return DepartureReadinessRules.readiness(balance.outstanding()).name();
    }
}
