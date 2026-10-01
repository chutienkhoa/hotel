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

/** Verifies Accompanying Guest resolution, validation and persistence in the create and draft-edit services. */
class ReservationAccompanyingGuestsServiceTest {

    private static final LocalDate IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 12);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ReservationNumberGenerator numbers = mock(ReservationNumberGenerator.class);
    private final ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ReservationService service = new ReservationService(
            reservations, guests, rooms, mock(StayRepository.class), mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class), mock(AuditLogRepository.class), new ReservationMapper(), numbers,
            mock(StayBalanceService.class), mock(com.example.hotel.service.room.RoomAvailabilityService.class), mock(com.example.hotel.service.booking.PrepaymentService.class), Clock.fixed(IN.atTime(10, 0).atZone(zone).toInstant(), zone));

    private Guest primary;
    private Guest second;
    private Guest third;
    private Room room;

    /** Wires three known guests and a room. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), "manager"), null, List.of()));
        primary = guest("G-1");
        second = guest("G-2");
        third = guest("G-3");
        RoomType type = mock(RoomType.class);
        when(type.getCapacity()).thenReturn(1);
        room = Room.create(UUID.randomUUID(), "101", type, "1");
        when(guests.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(guests.findById(second.getId())).thenReturn(Optional.of(second));
        when(guests.findAllById(any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(0);
            return List.of(primary, second, third).stream().filter(guest -> ids.contains(guest.getId())).toList();
        });
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(numbers.generate()).thenReturn("R20261010-000001");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creation persists the accompanying guests with the Reservation, independent of party size. */
    @Test
    void shouldPersistAccompanyingGuestsOnCreate() {
        service.create(request(primary, 1, 0, List.of(second.getId(), third.getId())));

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(captor.capture());
        assertEquals(List.of(second, third), captor.getValue().getAccompanyingGuests());
        assertEquals(1, captor.getValue().getPartySize());
    }

    /** Confirms an empty or absent accompanying list is valid. */
    @Test
    void shouldAcceptEmptyOrAbsentAccompanyingList() {
        service.create(request(primary, 2, 0, List.of()));
        service.create(request(primary, 2, 0, null));

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(saved -> assertEquals(0, saved.getAccompanyingGuests().size()));
    }

    /** Confirms an unknown accompanying Guest id is rejected with 404 and nothing is saved. */
    @Test
    void shouldRejectUnknownAccompanyingGuest() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.create(request(primary, 1, 0, List.of(UUID.randomUUID()))));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(reservations, never()).save(any());
    }

    /** Confirms duplicate ids and the primary guest id are rejected with 400. */
    @Test
    void shouldRejectDuplicateAndPrimaryGuestIds() {
        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> service.create(request(primary, 1, 0, List.of(second.getId(), second.getId()))));
        ResponseStatusException primaryAsCompanion = assertThrows(ResponseStatusException.class,
                () -> service.create(request(primary, 1, 0, List.of(primary.getId()))));

        assertEquals(HttpStatus.BAD_REQUEST, duplicate.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, primaryAsCompanion.getStatusCode());
        verify(reservations, never()).save(any());
    }

    /** Confirms a draft edit can add, remove and replace the set while keeping other data. */
    @Test
    void shouldAddRemoveAndReplaceOnDraftEdit() {
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, 2, 0,
                BookingSource.DIRECT, null, "VND", "keep");
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findRoomIdsByReservationId(draft.getId())).thenAnswer(invocation -> Optional.of(draft).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        service.updateDraft(draft.getId(), request(primary, 2, 0, List.of(second.getId())));
        assertEquals(List.of(second), draft.getAccompanyingGuests());
        service.updateDraft(draft.getId(), request(primary, 2, 0, List.of(second.getId(), third.getId())));
        assertEquals(List.of(second, third), draft.getAccompanyingGuests());
        service.updateDraft(draft.getId(), request(primary, 2, 0, List.of(third.getId())));
        assertEquals(List.of(third), draft.getAccompanyingGuests());
        service.updateDraft(draft.getId(), request(primary, 2, 0, List.of()));
        assertEquals(0, draft.getAccompanyingGuests().size());
    }

    /** Confirms changing the primary guest to one in the new accompanying list is rejected explicitly. */
    @Test
    void shouldRejectChangingPrimaryGuestIntoTheAccompanyingList() {
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, 2, 0,
                BookingSource.DIRECT, null, "VND", null);
        draft.replaceAccompanyingGuests(List.of(second), UUID.randomUUID());
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findRoomIdsByReservationId(draft.getId())).thenAnswer(invocation -> Optional.of(draft).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(draft.getId(), request(second, 2, 0, List.of(second.getId()))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(primary, draft.getGuest());
        assertEquals(List.of(second), draft.getAccompanyingGuests());
    }

    /** Confirms swapping roles (old primary becomes accompanying) is valid when the set is replaced together. */
    @Test
    void shouldAllowSwappingPrimaryAndAccompanyingTogether() {
        Reservation draft = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, 2, 0,
                BookingSource.DIRECT, null, "VND", null);
        draft.replaceAccompanyingGuests(List.of(second), UUID.randomUUID());
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(reservations.findRoomIdsByReservationId(draft.getId())).thenAnswer(invocation -> Optional.of(draft).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        service.updateDraft(draft.getId(), request(second, 2, 0, List.of(primary.getId())));

        assertEquals(second, draft.getGuest());
        assertEquals(List.of(primary), draft.getAccompanyingGuests());
    }

    /** Confirms a CONFIRMED reservation is not editable through Draft Edit and keeps its accompanying guests. */
    @Test
    void shouldNotEditAConfirmedReservation() {
        Reservation reservation = new Reservation(UUID.randomUUID(), "R-1", primary, IN, OUT, 2, 0,
                BookingSource.DIRECT, null, "VND", null);
        reservation.replaceAccompanyingGuests(List.of(second), UUID.randomUUID());
        reservation.confirm();
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservations.findRoomIdsByReservationId(reservation.getId())).thenAnswer(invocation -> Optional.of(reservation).map(r -> r.getRooms().stream().map(rr -> rr.getRoom().getId()).toList()).orElse(java.util.List.of()));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(reservation.getId(), request(primary, 2, 0, List.of())));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(List.of(second), reservation.getAccompanyingGuests());
    }

    private CreateRequest request(Guest primaryGuest, int adults, int children, List<UUID> accompanyingIds) {
        return new CreateRequest(primaryGuest.getId(), IN, OUT, adults, children, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room.getId(), BigDecimal.TEN)), accompanyingIds);
    }

    private static Guest guest(String code) {
        return Guest.create(UUID.randomUUID(), code, "Ann", "Lee", null, null, "Vietnam", null, null);
    }
}
