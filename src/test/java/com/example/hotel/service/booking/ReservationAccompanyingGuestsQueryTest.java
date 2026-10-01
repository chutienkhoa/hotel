package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.entity.booking.ReservationGuest;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ReservationGuestRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies Accompanying Guests are loaded with one repository call, however many there are (no per-Guest queries). */
class ReservationAccompanyingGuestsQueryTest {

    /** Confirms a single query loads every accompanying guest and maps identity only (Guest Code, no profile data). */
    @Test
    void shouldLoadAllAccompanyingGuestsWithOneRepositoryCall() {
        ReservationGuestRepository repository = mock(ReservationGuestRepository.class);
        UUID reservationId = UUID.randomUUID();
        Guest a = Guest.create(UUID.randomUUID(), "G-2", "Ann", "Lee", "a@x.com", "123", "Vietnam", null, null);
        Guest b = Guest.create(UUID.randomUUID(), "G-3", "Bo", "Tran", null, null, "Vietnam", null, null);
        Guest c = Guest.create(UUID.randomUUID(), "G-4", null, null, null, null, null, null, null);
        List<ReservationGuest> links = List.of(link(a), link(b), link(c));
        when(repository.findByReservationIdWithGuest(reservationId)).thenReturn(links);
        ReservationQueryService service = new ReservationQueryService(
                mock(ReservationRepository.class), new ReservationMapper(), repository);

        List<AccompanyingGuestResponse> result = service.findAccompanyingGuests(reservationId);

        assertEquals(List.of("G-2", "G-3", "G-4"), result.stream().map(AccompanyingGuestResponse::guestCode).toList());
        assertEquals(List.of(a.getId(), b.getId(), c.getId()), result.stream().map(AccompanyingGuestResponse::guestId).toList());
        verify(repository, times(1)).findByReservationIdWithGuest(reservationId);
    }

    private static ReservationGuest link(Guest guest) {
        ReservationGuest link = mock(ReservationGuest.class);
        when(link.getGuest()).thenReturn(guest);
        return link;
    }
}
