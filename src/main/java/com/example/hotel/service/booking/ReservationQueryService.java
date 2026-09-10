package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provides read-only reservation data for API and Thymeleaf presentation layers.
 */
@Service
public class ReservationQueryService {

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
        return reservationRepository.findAll().stream()
                .map(reservationMapper::toSummaryResponse)
                .toList();
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
}
