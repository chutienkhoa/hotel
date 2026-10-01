package com.example.hotel.entity.room;

/**
 * Lists the only Room states that remove a Room from sellable inventory. A Room inventory period
 * without a reason is sellable, so AVAILABLE, OCCUPIED, DIRTY and CLEANING have no representation.
 */
public enum RoomUnavailableReason {
    MAINTENANCE,
    OUT_OF_ORDER;

    /**
     * Maps an operational Room status to the reason that removes it from sellable inventory.
     *
     * @param status current operational Room status
     * @return the unavailable reason, or {@code null} when the status is part of sellable inventory
     */
    public static RoomUnavailableReason fromStatus(RoomStatus status) {
        return switch (status) {
            case MAINTENANCE -> MAINTENANCE;
            case OUT_OF_ORDER -> OUT_OF_ORDER;
            case AVAILABLE, OCCUPIED, DIRTY, CLEANING -> null;
        };
    }
}
