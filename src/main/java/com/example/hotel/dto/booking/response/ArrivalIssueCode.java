package com.example.hotel.dto.booking.response;

/** Stable machine code of an Arrival Readiness issue; localized at the presentation boundary. */
public enum ArrivalIssueCode {
    /** BLOCKER: the Reservation is not CONFIRMED. */
    RESERVATION_NOT_CONFIRMED,
    /** BLOCKER: the hotel current date is before the check-in date. */
    ARRIVAL_TOO_EARLY,
    /** BLOCKER: a Stay already exists for the Reservation. */
    STAY_ALREADY_EXISTS,
    /** BLOCKER: the assigned Room is inactive. */
    ROOM_INACTIVE,
    /** BLOCKER: the assigned Room is OCCUPIED. */
    ROOM_OCCUPIED,
    /** BLOCKER: the assigned Room is DIRTY and needs housekeeping. */
    ROOM_DIRTY,
    /** BLOCKER: the assigned Room is CLEANING. */
    ROOM_CLEANING,
    /** BLOCKER: the assigned Room is in MAINTENANCE. */
    ROOM_MAINTENANCE,
    /** BLOCKER: the assigned Room is OUT_OF_ORDER. */
    ROOM_OUT_OF_ORDER,
    /** BLOCKER: the adult count exceeds the summed adult capacity of the assigned rooms. */
    INSUFFICIENT_ADULT_CAPACITY,
    /** BLOCKER: an assigned Room's RoomType has no configured capacity, so capacity cannot be established. */
    CAPACITY_NOT_CONFIGURED,
    /** WARNING: the check-in date has passed (existing late check-in rule); no automatic NO_SHOW. */
    ARRIVAL_OVERDUE,
    /** WARNING: the Guest has no passport image (optional in V1). */
    PASSPORT_MISSING,
    /** INFO: the assigned Room is active and AVAILABLE. */
    ROOM_READY
}
