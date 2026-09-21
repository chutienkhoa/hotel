package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the create and draft-edit services carry guest composition, validate it, and do not enforce capacity. */
class ReservationGuestCompositionServiceTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ReservationNumberGenerator numbers = mock(ReservationNumberGenerator.class);
    private final ReservationService service = new ReservationService(
            reservations, guests, rooms, mock(StayRepository.class), mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class), mock(AuditLogRepository.class), new ReservationMapper(), numbers,
            mock(StayBalanceService.class), mock(com.example.hotel.service.room.RoomAvailabilityService.class), mock(com.example.hotel.service.booking.PrepaymentService.class), Clock.fixed(IN.atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                    ZoneId.of("Asia/Ho_Chi_Minh")));

    private Guest guest;
    private Room doubleRoom;

    /** Wires a guest and a capacity-2 room. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "manager"), null, List.of()));
        guest = Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);
        RoomType type = mock(RoomType.class);
        when(type.getCapacity()).thenReturn(2);
        doubleRoom = Room.create(UUID.randomUUID(), "101", type, "1");
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(rooms.findAllById(any())).thenReturn(List.of(doubleRoom));
        when(numbers.generate()).thenReturn("R20261010-000001");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms create persists exactly the supplied counts, including a party larger than the room capacity. */
    @Test
    void shouldPersistSuppliedCountsOnCreateEvenBeyondRoomCapacity() {
        service.create(request(3, 1));

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(captor.capture());
        assertEquals(3, captor.getValue().getAdultCount());
        assertEquals(1, captor.getValue().getChildCount());
        assertEquals(4, captor.getValue().getPartySize());
        assertEquals(ReservationStatus.DRAFT, captor.getValue().getStatus());
    }

    /** Confirms null, zero-adult and negative-child requests are rejected with 400 and nothing is saved. */
    @Test
    void shouldRejectInvalidCountsOnCreate() {
        for (CreateRequest invalid : List.of(request(null, 0), request(0, 0), request(-1, 0), request(1, null), request(1, -1))) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.create(invalid));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        }
        verify(reservations, never()).save(any());
    }

    /** Confirms a DRAFT edit changes and persists the counts, without capacity validation and with other data intact. */
    @Test
    void shouldUpdateCountsOfADraftAndKeepOtherData() {
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", guest, IN, OUT, 2, 0,
                BookingSource.BOOKING_COM, "BK-1", "VND", "keep");
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findRoomIdsByReservationId(draft.getId())).thenAnswer(invocation -> Optional.of(draft).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        service.updateDraft(draft.getId(), new CreateRequest(guest.getId(), IN, OUT, 3, 1, BookingSource.BOOKING_COM,
                "BK-1", "VND", "keep", List.of(new RoomRequest(doubleRoom.getId(), BigDecimal.TEN)), List.of()));

        assertEquals(3, draft.getAdultCount());
        assertEquals(1, draft.getChildCount());
        assertEquals("BK-1", draft.getOtaBookingReference());
        assertEquals("keep", draft.getNotes());
        assertEquals(1, draft.getRooms().size());
    }

    /** Confirms editing an invalid composition on a draft is rejected and leaves the stored counts alone. */
    @Test
    void shouldRejectInvalidCountsOnDraftEdit() {
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", guest, IN, OUT, 2, 1,
                BookingSource.DIRECT, null, "VND", null);
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findRoomIdsByReservationId(draft.getId())).thenAnswer(invocation -> Optional.of(draft).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        assertThrows(ResponseStatusException.class, () -> service.updateDraft(draft.getId(), request(0, 1)));

        assertEquals(2, draft.getAdultCount());
        assertEquals(1, draft.getChildCount());
    }

    /** Confirms a CONFIRMED reservation cannot be edited, so counts cannot change through draft edit. */
    @Test
    void shouldNotChangeCountsOfAConfirmedReservation() {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R-1", guest, IN, OUT, 2, 0,
                BookingSource.DIRECT, null, "VND", null);
        reservation.confirm();
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findRoomIdsByReservationId(reservation.getId())).thenAnswer(invocation -> Optional.of(reservation).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.updateDraft(reservation.getId(), request(4, 2)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(2, reservation.getAdultCount());
    }

    private CreateRequest request(Integer adults, Integer children) {
        return new CreateRequest(guest.getId(), IN, OUT, adults, children, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(doubleRoom.getId(), BigDecimal.TEN)), List.of());
    }
}
