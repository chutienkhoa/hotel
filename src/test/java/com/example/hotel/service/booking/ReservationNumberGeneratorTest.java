package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.example.hotel.repository.booking.ReservationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies generation of backend-owned reservation numbers. */
@ExtendWith(MockitoExtension.class)
class ReservationNumberGeneratorTest {

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationNumberGenerator reservationNumberGenerator;

    /** Confirms that generated numbers retain the required date and zero-padded daily sequence. */
    @Test
    void generatesApprovedDailyReservationNumberFormat() {
        when(reservationRepository.allocateReservationNumber())
                .thenReturn(Optional.of("R20260911-000001"));

        assertEquals("R20260911-000001", reservationNumberGenerator.generate());
    }

    /** Confirms that independently allocated daily values produce distinct reservation numbers. */
    @Test
    void generatesUniqueReservationNumbers() {
        when(reservationRepository.allocateReservationNumber())
                .thenReturn(Optional.of("R20260911-000001"));

        String firstReservationNumber = reservationNumberGenerator.generate();

        reset(reservationRepository);
        when(reservationRepository.allocateReservationNumber())
                .thenReturn(Optional.of("R20260911-000002"));

        String secondReservationNumber = reservationNumberGenerator.generate();

        assertNotEquals(firstReservationNumber, secondReservationNumber);
    }
}
