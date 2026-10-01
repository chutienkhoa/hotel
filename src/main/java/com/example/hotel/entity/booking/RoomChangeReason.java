package com.example.hotel.entity.booking;

/** Liệt kê các lý do được duyệt cho một lần đổi phòng sau khi check-in. */
public enum RoomChangeReason {
    GUEST_REQUEST,
    ROOM_ISSUE,
    UPGRADE,
    DOWNGRADE,
    OPERATIONAL,
    OTHER;

    /**
     * Returns the approved staff-facing label for this Room Change reason.
     *
     * @return the reason label used by Room Change and Room History presentation
     */
    public String getDisplayName() {
        return switch (this) {
            case GUEST_REQUEST -> "Guest request";
            case ROOM_ISSUE -> "Room issue";
            case UPGRADE -> "Upgrade";
            case DOWNGRADE -> "Downgrade";
            case OPERATIONAL -> "Operational";
            case OTHER -> "Other";
        };
    }
}
