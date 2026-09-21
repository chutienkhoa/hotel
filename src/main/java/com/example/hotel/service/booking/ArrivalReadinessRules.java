package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.room.Room;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The single rule set behind both the derived Arrival Readiness and the authoritative check-in operation. Pure
 * functions with no persistence: {@link ReservationService#checkIn} calls {@link #reservationBlockers} and
 * {@link #roomBlocker} for its validation, and the readiness read model composes the very same functions, so the
 * two cannot silently disagree. Blockers here are exactly the conditions check-in rejects; everything else is a
 * WARNING or INFO.
 */
public final class ArrivalReadinessRules {

    private ArrivalReadinessRules() {}

    /**
     * Classifies the hotel current date against the planned check-in date.
     *
     * @param today hotel current date
     * @param checkInDate planned check-in date
     * @return EARLY, NORMAL or LATE
     */
    public static CheckInTiming classify(LocalDate today, LocalDate checkInDate) {
        if (today.isBefore(checkInDate)) {
            return CheckInTiming.EARLY;
        }
        return today.isAfter(checkInDate) ? CheckInTiming.LATE : CheckInTiming.NORMAL;
    }

    /**
     * Lists the Reservation-level blockers in the order check-in evaluates them: not CONFIRMED, arrival too early,
     * Stay already exists. Like check-in, the Stay lookup is only made when no earlier blocker applies.
     *
     * @param status Reservation status
     * @param checkInDate planned check-in date
     * @param today hotel current date
     * @param stayExists lazily answers whether a Stay already exists for the Reservation
     * @return blocker codes in evaluation order
     */
    public static List<ArrivalIssueCode> reservationBlockers(
            ReservationStatus status, LocalDate checkInDate, LocalDate today, BooleanSupplier stayExists) {
        List<ArrivalIssueCode> blockers = new ArrayList<>();
        if (status != ReservationStatus.CONFIRMED) {
            blockers.add(ArrivalIssueCode.RESERVATION_NOT_CONFIRMED);
        }
        if (today.isBefore(checkInDate)) {
            blockers.add(ArrivalIssueCode.ARRIVAL_TOO_EARLY);
        }
        if (blockers.isEmpty() && stayExists.getAsBoolean()) {
            blockers.add(ArrivalIssueCode.STAY_ALREADY_EXISTS);
        }
        return blockers;
    }

    /**
     * Returns the blocker for a Room that cannot accept a guest now. A Room is check-in-ready only when it is
     * active and AVAILABLE ({@link Room#isReadyForCheckIn()}).
     *
     * @param room assigned Room
     * @return the blocker code, or empty when the Room is check-in-ready
     */
    public static Optional<ArrivalIssueCode> roomBlocker(Room room) {
        if (!room.isActive()) {
            return Optional.of(ArrivalIssueCode.ROOM_INACTIVE);
        }
        return switch (room.getStatus()) {
            case AVAILABLE -> Optional.empty();
            case OCCUPIED -> Optional.of(ArrivalIssueCode.ROOM_OCCUPIED);
            case DIRTY -> Optional.of(ArrivalIssueCode.ROOM_DIRTY);
            case CLEANING -> Optional.of(ArrivalIssueCode.ROOM_CLEANING);
            case MAINTENANCE -> Optional.of(ArrivalIssueCode.ROOM_MAINTENANCE);
            case OUT_OF_ORDER -> Optional.of(ArrivalIssueCode.ROOM_OUT_OF_ORDER);
        };
    }

    /**
     * Derives the Arrival Readiness. A non-CONFIRMED Reservation reports only its state blocker. Otherwise every
     * assigned Room is reported individually (blocker or INFO ready), plus an overdue WARNING for a past-due
     * check-in date and a passport WARNING when none is uploaded.
     *
     * @param status Reservation status
     * @param checkInDate planned check-in date
     * @param today hotel current date
     * @param stayExists whether a Stay already exists
     * @param rooms assigned Rooms
     * @param passportAvailable whether the Guest has a passport image
     * @return the derived readiness
     */
    public static ArrivalReadiness evaluate(
            ReservationStatus status,
            LocalDate checkInDate,
            LocalDate today,
            boolean stayExists,
            List<Room> rooms,
            boolean passportAvailable) {
        CheckInTiming timing = classify(today, checkInDate);
        List<ArrivalReadinessIssue> blockers = new ArrayList<>();
        List<ArrivalReadinessIssue> warnings = new ArrayList<>();
        List<ArrivalReadinessIssue> info = new ArrayList<>();
        for (ArrivalIssueCode code : reservationBlockers(status, checkInDate, today, () -> stayExists)) {
            blockers.add(new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, code, null));
        }
        if (status == ReservationStatus.CONFIRMED) {
            for (Room room : rooms) {
                Optional<ArrivalIssueCode> blocker = roomBlocker(room);
                if (blocker.isPresent()) {
                    blockers.add(new ArrivalReadinessIssue(
                            ArrivalIssueSeverity.BLOCKER, blocker.get(), room.getRoomNumber(), room.getId()));
                } else {
                    info.add(new ArrivalReadinessIssue(
                            ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, room.getRoomNumber(), room.getId()));
                }
            }
            if (timing == CheckInTiming.LATE) {
                warnings.add(new ArrivalReadinessIssue(
                        ArrivalIssueSeverity.WARNING, ArrivalIssueCode.ARRIVAL_OVERDUE, null));
            }
            if (!passportAvailable) {
                warnings.add(new ArrivalReadinessIssue(
                        ArrivalIssueSeverity.WARNING, ArrivalIssueCode.PASSPORT_MISSING, null));
            }
        }
        List<ArrivalReadinessIssue> issues = new ArrayList<>(blockers);
        issues.addAll(warnings);
        issues.addAll(info);
        ArrivalReadinessState state = blockers.isEmpty() ? ArrivalReadinessState.READY : ArrivalReadinessState.NEEDS_ATTENTION;
        return new ArrivalReadiness(state, timing, List.copyOf(issues));
    }
}
