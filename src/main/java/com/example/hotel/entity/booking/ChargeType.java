package com.example.hotel.entity.booking;

/** Defines current and reserved charge classifications. */
public enum ChargeType {
    ROOM,
    BREAKFAST,
    EXTRA_BED,
    LAUNDRY,
    MINIBAR,
    SERVICE,
    TAX,
    DISCOUNT,
    OTHER;

    /**
     * Determines whether this type may be created by the approved Charge v1 operations.
     *
     * @return {@code true} when Charge v1 supports creating this type
     */
    public boolean isSupportedInV1() {
        return this != TAX && this != DISCOUNT;
    }
}
