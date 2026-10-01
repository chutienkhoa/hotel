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
     * Determines whether a Charge of this type is guest service revenue (it creates exactly one linked
     * Additional Revenue). ROOM has its own accommodation revenue sources; TAX and DISCOUNT are unsupported in V1.
     *
     * @return {@code true} for BREAKFAST, EXTRA_BED, LAUNDRY, MINIBAR, SERVICE and OTHER
     */
    public boolean isGuestServiceRevenue() {
        return this != ROOM && isSupportedInV1();
    }

    /**
     * Returns the stable code of the system Additional Revenue category of this guest service type.
     *
     * @return {@code GUEST_<TYPE>}
     */
    public String guestServiceCategoryCode() {
        return "GUEST_" + name();
    }

    /**
     * Determines whether this type may be created by the approved Charge v1 operations.
     *
     * @return {@code true} when Charge v1 supports creating this type
     */
    public boolean isSupportedInV1() {
        return this != TAX && this != DISCOUNT;
    }
}
