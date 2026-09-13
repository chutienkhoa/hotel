package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.StayResponse;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.StayMapper;
import com.example.hotel.repository.booking.StayRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the Stay query used by reservation-scoped Folio presentation. */
class StayQueryServiceTest {

    /** Confirms the query maps the Stay belonging to the requested Reservation. */
    @Test
    void shouldFindAndMapStayByReservationId() {
        StayRepository stayRepository = mock(StayRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID stayId = UUID.randomUUID();
        Instant checkedInAt = Instant.parse("2026-01-01T10:00:00Z");
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(stayId);
        when(stay.getStatus()).thenReturn(StayStatus.CHECKED_IN);
        when(stay.getActualCheckInAt()).thenReturn(checkedInAt);
        when(stay.getActualCheckOutAt()).thenReturn(null);
        when(stayRepository.findByReservationId(reservationId)).thenReturn(Optional.of(stay));

        StayResponse response = new StayQueryService(stayRepository, new StayMapper())
                .findByReservationId(reservationId);

        assertEquals(stayId, response.id());
        assertEquals("CHECKED_IN", response.status());
        assertEquals(checkedInAt, response.actualCheckInAt());
        assertEquals(null, response.actualCheckOutAt());
    }

    /** Confirms a Reservation without a Stay receives the standard not-found response. */
    @Test
    void shouldRejectReservationWithoutStay() {
        StayRepository stayRepository = mock(StayRepository.class);
        UUID reservationId = UUID.randomUUID();
        when(stayRepository.findByReservationId(reservationId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> new StayQueryService(stayRepository, new StayMapper())
                        .findByReservationId(reservationId));

        assertEquals(404, exception.getStatusCode().value());
    }
}
