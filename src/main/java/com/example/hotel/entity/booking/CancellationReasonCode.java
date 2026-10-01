package com.example.hotel.entity.booking;

/** Liệt kê các lý do hủy được duyệt cho một Reservation CONFIRMED. */
public enum CancellationReasonCode {
    GUEST_REQUEST,
    CHANGE_OF_PLANS,
    DUPLICATE_BOOKING,
    PAYMENT_ISSUE,
    HOTEL_OPERATIONAL,
    OTA_CANCELLATION,
    OTHER;

    /**
     * Returns the approved staff-facing label for this cancellation reason.
     *
     * @return the reason label used by cancellation and Reservation Detail presentation
     */
    public String getDisplayName() {
        return switch (this) {
            case GUEST_REQUEST -> "Guest request";
            case CHANGE_OF_PLANS -> "Change of plans";
            case DUPLICATE_BOOKING -> "Duplicate booking";
            case PAYMENT_ISSUE -> "Payment issue";
            case HOTEL_OPERATIONAL -> "Hotel operational";
            case OTA_CANCELLATION -> "OTA cancellation";
            case OTHER -> "Other";
        };
    }
}
