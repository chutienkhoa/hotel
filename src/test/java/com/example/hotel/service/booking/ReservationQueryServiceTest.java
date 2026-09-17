package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.ReservationSearchCriteria;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Verifies database-backed Reservation list pagination configuration. */
class ReservationQueryServiceTest {

    /** Confirms the list query uses a fixed page size and stable approved ordering. */
    @Test
    void shouldQueryTenReservationsWithStableOrdering() {
        ReservationRepository repository = mock(ReservationRepository.class);
        ReservationMapper mapper = mock(ReservationMapper.class);
        Reservation reservation = mock(Reservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservation.getId()).thenReturn(reservationId);
        ReservationSummaryResponse summary = new ReservationSummaryResponse(
                reservationId,
                "R20260912-000001",
                "Nguyen Van A",
                "101",
                "CONFIRMED",
                BookingSource.DIRECT,
                null,
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 9, 13),
                null,
                "VND");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reservation), Pageable.ofSize(10).withPage(1), 11));
        when(repository.findRoomNumbersByReservationIdIn(ArgumentMatchers.anyList())).thenReturn(List.of());
        when(mapper.toSummaryResponse(reservation, "")).thenReturn(summary);

        var result = new ReservationQueryService(repository, mapper)
                .findPage(new ReservationSearchCriteria(), 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("checkInDate").getDirection());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("reservationNumber").getDirection());
        assertEquals(11, result.getTotalElements());
        assertEquals(List.of(summary), result.getContent());
    }
}
