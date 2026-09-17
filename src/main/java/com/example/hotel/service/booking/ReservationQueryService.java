package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provides read-only reservation data for API and Thymeleaf presentation layers.
 */
@Service
public class ReservationQueryService {

    private static final int RESERVATION_PAGE_SIZE = 10;

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    /**
     * Creates the query service with the dependencies required to load and map reservations.
     *
     * @param reservationRepository repository used to load reservations
     * @param reservationMapper mapper used to create response DTOs
     */
    public ReservationQueryService(
            ReservationRepository reservationRepository, ReservationMapper reservationMapper) {
        this.reservationRepository = reservationRepository;
        this.reservationMapper = reservationMapper;
    }

    /**
     * Retrieves every reservation as a compact list representation.
     *
     * @return the reservation list representations
     */
    @Transactional(readOnly = true)
    public List<ReservationSummaryResponse> findAll() {
        List<Reservation> reservations = reservationRepository.findAll();
        Map<UUID, String> roomNumbersByReservationId = roomNumbersByReservationId(reservations);
        return reservations.stream()
                .map(reservation -> reservationMapper.toSummaryResponse(
                        reservation, roomNumbersByReservationId.getOrDefault(reservation.getId(), "")))
                .toList();
    }

    /**
     * Retrieves one server-side page of Reservations matching the supplied optional criteria.
     *
     * @param criteria normalized optional list filters
     * @param page zero-based requested page number
     * @return a page of compact Reservation list representations
     */
    @Transactional(readOnly = true)
    public Page<ReservationSummaryResponse> findPage(ReservationSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                RESERVATION_PAGE_SIZE,
                Sort.by(Sort.Order.desc("checkInDate"), Sort.Order.asc("reservationNumber")));
        Page<Reservation> reservationPage = reservationRepository.findAll(specificationFor(criteria), pageable);
        Map<UUID, String> roomNumbersByReservationId = roomNumbersByReservationId(reservationPage.getContent());
        return reservationPage.map(reservation -> reservationMapper.toSummaryResponse(
                reservation, roomNumbersByReservationId.getOrDefault(reservation.getId(), "")));
    }

    /**
     * Batch-loads and joins each Reservation's assigned room numbers in a single extra query,
     * avoiding an N+1 lookup per row and any duplicate Reservation rows a room join would cause.
     *
     * @param reservations Reservations whose assigned room numbers are loaded
     * @return each Reservation identifier mapped to its compact, comma-separated room numbers
     */
    private Map<UUID, String> roomNumbersByReservationId(List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return Map.of();
        }
        List<UUID> reservationIds = reservations.stream().map(Reservation::getId).toList();
        Map<UUID, List<String>> roomNumbersById = new LinkedHashMap<>();
        for (Object[] row : reservationRepository.findRoomNumbersByReservationIdIn(reservationIds)) {
            UUID reservationId = (UUID) row[0];
            String roomNumber = (String) row[1];
            roomNumbersById.computeIfAbsent(reservationId, id -> new ArrayList<>()).add(roomNumber);
        }
        Map<UUID, String> joined = new LinkedHashMap<>();
        roomNumbersById.forEach((reservationId, roomNumbers) -> joined.put(reservationId, String.join(", ", roomNumbers)));
        return joined;
    }

    /** Builds the database predicate containing only the supplied filters. */
    private Specification<Reservation> specificationFor(ReservationSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            if (criteria.getReservationNumber() != null) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("reservationNumber")),
                        "%" + criteria.getReservationNumber().toLowerCase(Locale.ROOT) + "%"));
            }
            if (criteria.getGuest() != null) {
                Join<Reservation, Guest> guestJoin = root.join("guest");
                String pattern = "%" + criteria.getGuest().toLowerCase(Locale.ROOT) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(guestJoin.get("firstName")), pattern),
                        criteriaBuilder.like(criteriaBuilder.lower(guestJoin.get("lastName")), pattern)));
            }
            if (criteria.getRoom() != null) {
                Join<Reservation, ReservationRoom> roomJoin = root.join("rooms");
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(roomJoin.get("room").get("roomNumber")),
                        "%" + criteria.getRoom().toLowerCase(Locale.ROOT) + "%"));
                query.distinct(true);
            }
            if (criteria.getSource() != null) {
                predicates.add(criteriaBuilder.equal(root.get("source"), criteria.getSource()));
            }
            if (criteria.getOtaBookingReference() != null) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("otaBookingReference")),
                        "%" + criteria.getOtaBookingReference().toLowerCase(Locale.ROOT) + "%"));
            }
            if (criteria.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), criteria.getStatus()));
            }
            if (criteria.getCheckInFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("checkInDate"), criteria.getCheckInFrom()));
            }
            if (criteria.getCheckInTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("checkInDate"), criteria.getCheckInTo()));
            }
            if (criteria.getCheckOutFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("checkOutDate"), criteria.getCheckOutFrom()));
            }
            if (criteria.getCheckOutTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("checkOutDate"), criteria.getCheckOutTo()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Retrieves one reservation as a detail representation.
     *
     * @param reservationId the reservation identifier
     * @return the reservation detail representation
     * @throws ResponseStatusException if no reservation exists for the identifier
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse findById(UUID reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        return reservationMapper.toDetailResponse(reservation);
    }

    /** Retrieves one Reservation in the representation required by the draft edit form. */
    @Transactional(readOnly = true)
    public ReservationEditResponse findForEdit(UUID reservationId) {
        Reservation reservation = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        return reservationMapper.toEditResponse(reservation);
    }
}
