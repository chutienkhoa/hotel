package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.mapper.booking.StayMapper;
import com.example.hotel.repository.booking.StayRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Provides read-only Stay data for Folio and checkout-readiness presentation. */
@Service
public class StayQueryService {

    private final StayRepository stayRepository;
    private final StayMapper stayMapper;

    /**
     * Creates the query service with persistence and mapping dependencies.
     *
     * @param stayRepository repository used to load Stays
     * @param stayMapper mapper used to create presentation-safe responses
     */
    public StayQueryService(StayRepository stayRepository, StayMapper stayMapper) {
        this.stayRepository = stayRepository;
        this.stayMapper = stayMapper;
    }

    /**
     * Finds the unique Stay associated with a Reservation.
     *
     * @param reservationId Reservation identifier
     * @return the matching Stay response
     * @throws ResponseStatusException if the Reservation has no Stay
     */
    @Transactional(readOnly = true)
    public StayResponse findByReservationId(UUID reservationId) {
        Stay stay = stayRepository
                .findByReservationId(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
        return stayMapper.toResponse(stay);
    }
}
