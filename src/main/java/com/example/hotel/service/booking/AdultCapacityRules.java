package com.example.hotel.service.booking;

import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single V1 adult-capacity rule: {@code adultCount <= SUM(RoomType.capacity)} over the Reservation's assigned
 * Rooms. Capacity is ADULT capacity and is aggregated across the whole Reservation (no per-room occupant assignment).
 * Children, party size and Accompanying Guest profiles never take part. A RoomType whose capacity is {@code null} is a
 * configuration error (unknown capacity): it is never treated as zero, as unlimited, or skipped.
 *
 * <p>Pure functions with no persistence and no localized text, so Reservation confirmation, Arrival Readiness, check-in
 * and pre-check-in Room Reassignment all call exactly this rule and cannot disagree.</p>
 */
public final class AdultCapacityRules {

    /** Outcome of the capacity rule. */
    public enum Outcome {
        /** The assigned rooms support the adults. */
        VALID,
        /** Configured capacity is lower than the number of adults. */
        INSUFFICIENT_ADULT_CAPACITY,
        /** At least one assigned Room's RoomType has no configured capacity, so capacity cannot be established. */
        CAPACITY_NOT_CONFIGURED
    }

    /**
     * Structured capacity result.
     *
     * @param outcome the outcome
     * @param adultCount the adults being accommodated
     * @param totalAdultCapacity the summed capacity, or {@code null} when it could not be established
     * @param unconfiguredRoomTypes names of the RoomTypes without configured capacity (empty unless
     *     {@link Outcome#CAPACITY_NOT_CONFIGURED})
     */
    public record Result(Outcome outcome, int adultCount, Integer totalAdultCapacity, List<String> unconfiguredRoomTypes) {

        /**
         * Tells whether capacity is sufficient.
         *
         * @return {@code true} for {@link Outcome#VALID}
         */
        public boolean valid() {
            return outcome == Outcome.VALID;
        }
    }

    private AdultCapacityRules() {}

    /**
     * Evaluates the rule for the given assigned Rooms. Any Room without a configured RoomType capacity makes the
     * result CAPACITY_NOT_CONFIGURED; otherwise the configured capacities are summed and compared with the adults.
     *
     * @param adultCount the Reservation's adult count
     * @param rooms every assigned Room (the final set the Reservation would hold)
     * @return the structured result
     */
    public static Result evaluate(int adultCount, Collection<Room> rooms) {
        Set<String> unconfigured = new LinkedHashSet<>();
        int total = 0;
        for (Room room : rooms) {
            RoomType type = room.getRoomType();
            Integer capacity = type == null ? null : type.getCapacity();
            if (capacity == null) {
                unconfigured.add(type == null || type.getName() == null ? "?" : type.getName());
            } else {
                total += capacity;
            }
        }
        if (!unconfigured.isEmpty()) {
            return new Result(Outcome.CAPACITY_NOT_CONFIGURED, adultCount, null, List.copyOf(unconfigured));
        }
        return new Result(
                adultCount <= total ? Outcome.VALID : Outcome.INSUFFICIENT_ADULT_CAPACITY, adultCount, total, List.of());
    }
}
