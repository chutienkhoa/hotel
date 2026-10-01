package com.example.hotel.service.booking;

import com.example.hotel.repository.booking.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Allocates immutable, daily reservation numbers from the database. */
@Component
public class ReservationNumberGenerator {

    private final ReservationRepository reservationRepository;

    /**
     * Creates the generator with the repository that atomically allocates daily sequence values.
     *
     * @param reservationRepository repository used to allocate reservation numbers
     */
    public ReservationNumberGenerator(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /**
     * Allocates the next immutable reservation number in the approved format.
     *
     * @return the backend-generated reservation number
     * @throws ResponseStatusException if the daily six-digit sequence is exhausted
     */
    public String generate() {
        return reservationRepository
                .allocateReservationNumber()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Reservation number sequence is exhausted for today"));
    }
}
