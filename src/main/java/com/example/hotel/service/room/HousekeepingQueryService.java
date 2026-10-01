package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.HousekeepingRoomResponse;
import com.example.hotel.dto.room.response.HousekeepingWorkspaceResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomStatus;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.RoomNextArrivalRow;
import com.example.hotel.repository.room.RoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the read-only Housekeeping workspace: active Rooms grouped by housekeeping-relevant RoomStatus, with the
 * nearest upcoming arrival per Room. Only CONFIRMED Reservations whose check-in date is on or after the hotel
 * current date count as upcoming arrivals; DRAFT, CANCELLED, NO_SHOW, CHECKED_IN and CHECKED_OUT never do. Inactive
 * Rooms and OCCUPIED Rooms are not part of the worklist.
 */
@Service
public class HousekeepingQueryService {

    /** Reservation statuses that represent a guest who is still to arrive. */
    public static final List<ReservationStatus> UPCOMING_ARRIVAL_STATUSES = List.of(ReservationStatus.CONFIRMED);

    private static final Comparator<HousekeepingRoomResponse> BY_ROOM_NUMBER =
            Comparator.comparing(HousekeepingRoomResponse::roomNumber);
    private static final Comparator<HousekeepingRoomResponse> DIRTY_PRIORITY = Comparator
            .comparing(HousekeepingRoomResponse::urgent, Comparator.reverseOrder())
            .thenComparing(HousekeepingRoomResponse::nextArrivalDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BY_ROOM_NUMBER);

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param roomRepository source of active Rooms
     * @param reservationRepository source of next-arrival dates
     * @param clock hotel business clock
     */
    public HousekeepingQueryService(
            RoomRepository roomRepository, ReservationRepository reservationRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /**
     * Loads the workspace with two queries: active Rooms and grouped next arrivals.
     *
     * @return the grouped, ordered worklist
     */
    @Transactional(readOnly = true)
    public HousekeepingWorkspaceResponse loadWorkspace() {
        LocalDate today = LocalDate.now(clock);
        Map<UUID, LocalDate> arrivals = new HashMap<>();
        for (RoomNextArrivalRow row : reservationRepository.findNextArrivalsFrom(today, UPCOMING_ARRIVAL_STATUSES)) {
            arrivals.put(row.roomId(), row.nextArrivalDate());
        }
        List<HousekeepingRoomResponse> dirty = new ArrayList<>();
        List<HousekeepingRoomResponse> cleaning = new ArrayList<>();
        List<HousekeepingRoomResponse> ready = new ArrayList<>();
        List<HousekeepingRoomResponse> issues = new ArrayList<>();
        for (Room room : roomRepository.findActiveWithRoomType()) {
            HousekeepingRoomResponse row = toRow(room, arrivals.get(room.getId()), today);
            switch (room.getStatus()) {
                case DIRTY -> dirty.add(row);
                case CLEANING -> cleaning.add(row);
                case AVAILABLE -> ready.add(row);
                case MAINTENANCE, OUT_OF_ORDER -> issues.add(row);
                case OCCUPIED -> { }
            }
        }
        dirty.sort(DIRTY_PRIORITY);
        cleaning.sort(BY_ROOM_NUMBER);
        ready.sort(BY_ROOM_NUMBER);
        issues.sort(BY_ROOM_NUMBER);
        return new HousekeepingWorkspaceResponse(today, dirty, cleaning, ready, issues);
    }

    private HousekeepingRoomResponse toRow(Room room, LocalDate arrival, LocalDate today) {
        String kind = arrival == null
                ? "NONE"
                : arrival.equals(today) ? "TODAY" : arrival.equals(today.plusDays(1)) ? "TOMORROW" : "DATE";
        boolean urgent = room.getStatus() == RoomStatus.DIRTY && "TODAY".equals(kind);
        return new HousekeepingRoomResponse(
                room.getId(),
                room.getRoomNumber(),
                room.getRoomType().getName(),
                room.getFloor(),
                room.getStatus().name(),
                arrival,
                kind,
                urgent);
    }
}
