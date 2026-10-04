package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.response.CurrentRoomResponse;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayRoomAssignment;
import com.example.hotel.entity.room.Room;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the rooms released at checkout are told apart from rooms vacated earlier by a Room Change. */
class StayRoomAssignmentFinalRoomsTest {

    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID STAY_ID = UUID.randomUUID();
    private static final Instant CHANGED = Instant.parse("2026-10-03T05:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-10-07T04:25:00Z");

    private final StayRepository stays = mock(StayRepository.class);
    private final StayRoomAssignmentRepository assignments = mock(StayRoomAssignmentRepository.class);
    private final StayRoomAssignmentQueryService service =
            new StayRoomAssignmentQueryService(stays, assignments, mock(AppUserRepository.class));

    private StayRoomAssignment assignment(String number, UUID roomId, Instant to, String rate) {
        StayRoomAssignment assignment = mock(StayRoomAssignment.class);
        Room room = mock(Room.class);
        when(room.getId()).thenReturn(roomId);
        when(room.getRoomNumber()).thenReturn(number);
        ReservationRoom original = mock(ReservationRoom.class);
        when(original.getNightlyRate()).thenReturn(new BigDecimal(rate));
        when(assignment.getId()).thenReturn(UUID.randomUUID());
        when(assignment.getRoom()).thenReturn(room);
        when(assignment.getAssignedTo()).thenReturn(to);
        when(assignment.getAssignedFrom()).thenReturn(CHANGED.minusSeconds(86400));
        when(assignment.getOriginalReservationRoom()).thenReturn(original);
        return assignment;
    }

    private void stay(List<StayRoomAssignment> rows) {
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(STAY_ID);
        when(stays.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(stay));
        when(assignments.findByStayIdOrderByLineageAndTime(STAY_ID)).thenReturn(rows);
    }

    /** Confirms a room vacated by a Room Change is excluded while every room closed by the checkout is included. */
    @Test
    void shouldReturnOnlyRoomsClosedByTheCheckout() {
        UUID room102 = UUID.randomUUID();
        UUID room201 = UUID.randomUUID();
        stay(List.of(
                assignment("101", UUID.randomUUID(), CHANGED, "1000000"),
                assignment("102", room102, CLOSED, "1200000"),
                assignment("201", room201, CLOSED, "1500000")));

        List<CurrentRoomResponse> rooms = service.findFinalRooms(RESERVATION_ID);

        assertEquals(List.of("102", "201"), rooms.stream().map(CurrentRoomResponse::roomNumber).toList());
        assertEquals(new BigDecimal("1200000"), service.findFinalRoomRates(RESERVATION_ID).get(room102));
        assertEquals(2, service.findFinalRoomRates(RESERVATION_ID).size());
    }

    /** Confirms an open Stay (an assignment still open) has no final rooms. */
    @Test
    void shouldReturnNoFinalRoomsWhileTheStayIsStillOpen() {
        stay(List.of(assignment("101", UUID.randomUUID(), null, "1000000")));

        assertTrue(service.findFinalRooms(RESERVATION_ID).isEmpty());
        assertTrue(service.findFinalRoomRates(RESERVATION_ID).isEmpty());
    }
}
