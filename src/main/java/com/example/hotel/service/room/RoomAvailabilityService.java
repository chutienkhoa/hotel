package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.RoomLookupResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.room.RoomRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers which Rooms can be offered for a stay period, keeping two concepts apart. BOOKING availability is
 * about a requested period: the Room is bookable inventory and has no overlapping CONFIRMED or CHECKED_IN
 * Reservation (existing half-open overlap semantics: a stay ending on a date does not block one starting on it).
 * CHECK-IN readiness is about right now: the Room must additionally be AVAILABLE, the only check-in-ready
 * RoomStatus in V1. Current OCCUPIED, DIRTY or CLEANING status never blocks a future booking.
 */
@Service
public class RoomAvailabilityService {

    /** Reservation statuses that occupy a Room for their booked dates. */
    public static final List<ReservationStatus> BLOCKING_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;

    /**
     * Creates the service.
     *
     * @param roomRepository source of active Rooms
     * @param reservationRepository source of the date-overlap check
     */
    public RoomAvailabilityService(RoomRepository roomRepository, ReservationRepository reservationRepository) {
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * Lists Rooms that can be BOOKED for {@code [checkInDate, checkOutDate)}: bookable inventory without an
     * overlapping Reservation. Current RoomStatus OCCUPIED, DIRTY or CLEANING does not exclude a Room.
     *
     * @param checkInDate inclusive requested check-in date
     * @param checkOutDate exclusive requested check-out date
     * @return matching Rooms ordered by room number
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> bookableRoomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomsForPeriod(checkInDate, checkOutDate, Room::isBookableInventory);
    }

    /**
     * Lists Rooms that can be checked in IMMEDIATELY for {@code [checkInDate, checkOutDate)}: active AVAILABLE
     * Rooms without an overlapping Reservation. DIRTY, CLEANING, MAINTENANCE, OUT_OF_ORDER and OCCUPIED Rooms are
     * not offered.
     *
     * @param checkInDate inclusive requested check-in date (the hotel current date for a Walk-in)
     * @param checkOutDate exclusive requested check-out date
     * @return matching Rooms ordered by room number
     */
    @Transactional(readOnly = true)
    public List<RoomLookupResponse> checkInReadyRoomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate) {
        return roomsForPeriod(checkInDate, checkOutDate, Room::isReadyForCheckIn);
    }

    /**
     * Tells whether one Room can be checked in IMMEDIATELY for {@code [checkInDate, checkOutDate)}: it is active
     * AVAILABLE and has no overlapping CONFIRMED or CHECK_IN Reservation. This is the single predicate behind
     * {@link #checkInReadyRoomsForPeriod} and pre-check-in Room reassignment.
     *
     * @param room the Room to test
     * @param checkInDate inclusive check-in date
     * @param checkOutDate exclusive check-out date
     * @return {@code true} when the Room is check-in-ready and conflict-free for the period
     */
    public boolean isCheckInReadyForPeriod(Room room, LocalDate checkInDate, LocalDate checkOutDate) {
        return room.isReadyForCheckIn()
                && !reservationRepository.hasOverlap(room.getId(), checkInDate, checkOutDate, BLOCKING_STATUSES);
    }

    private List<RoomLookupResponse> roomsForPeriod(LocalDate checkInDate, LocalDate checkOutDate, Predicate<Room> eligible) {
        return roomRepository.findByActiveTrue().stream()
                .filter(eligible)
                .filter(room -> !reservationRepository.hasOverlap(room.getId(), checkInDate, checkOutDate, BLOCKING_STATUSES))
                .sorted(Comparator.comparing(Room::getRoomNumber))
                .map(room -> new RoomLookupResponse(room.getId(), room.getRoomNumber(), room.getStatus().name(), room.isActive()))
                .toList();
    }
}
