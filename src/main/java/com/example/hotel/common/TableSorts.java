package com.example.hotel.common;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;

/**
 * The sort whitelists of the primary data-table screens. Public sort keys map to fixed, known-safe
 * entity properties; anything else falls back to each screen's default ordering.
 */
public final class TableSorts {

    /** Reservation list. */
    public static final SortWhitelist RESERVATION = new SortWhitelist(
            Map.of(
                    "reservationNumber", "reservationNumber",
                    "checkInDate", "checkInDate",
                    "checkOutDate", "checkOutDate",
                    "status", "status"),
            Sort.by(Sort.Order.desc("checkInDate"), Sort.Order.asc("reservationNumber")),
            List.of(Sort.Order.asc("reservationNumber")));

    /** Check-in Existing Reservation search. */
    public static final SortWhitelist CHECK_IN = RESERVATION.restrictedTo("reservationNumber", "checkInDate");

    /** Check-out search. */
    public static final SortWhitelist CHECK_OUT = RESERVATION.restrictedTo("reservationNumber", "checkOutDate");

    /** Guest list. */
    public static final SortWhitelist GUEST = new SortWhitelist(
            Map.of("guestCode", "guestCode", "firstName", "firstName", "lastName", "lastName"),
            Sort.by(Sort.Order.asc("guestCode")),
            List.of(Sort.Order.asc("guestCode")));

    /** Room list. */
    public static final SortWhitelist ROOM = new SortWhitelist(
            Map.of("roomNumber", "roomNumber", "roomType", "roomType.code", "floor", "floor", "status", "status"),
            Sort.by(Sort.Order.asc("roomNumber")),
            List.of(Sort.Order.asc("roomNumber")));

    /** Expense list. */
    public static final SortWhitelist EXPENSE = new SortWhitelist(
            Map.of("date", "expenseDate", "amount", "amount", "status", "status"),
            Sort.by(Sort.Order.desc("expenseDate"), Sort.Order.desc("id")),
            List.of(Sort.Order.desc("expenseDate"), Sort.Order.desc("id")));

    /** Additional Revenue list. */
    public static final SortWhitelist ADDITIONAL_REVENUE = new SortWhitelist(
            Map.of("date", "revenueDate", "amount", "amount", "status", "status"),
            Sort.by(Sort.Order.desc("revenueDate"), Sort.Order.desc("id")),
            List.of(Sort.Order.desc("revenueDate"), Sort.Order.desc("id")));

    /**
     * Front Desk Arrivals (Batch 3A). Front Desk sorts its already-loaded read model in memory, not
     * through a database {@code Sort}, so only {@link SortWhitelist#key(String, String)} and
     * {@link SortWhitelist#activeDirection(String, String)} are used; the {@code defaultSort}/
     * tie-breaker fields below are unused placeholders kept only so this whitelist has the same
     * shape as every other screen's.
     */
    public static final SortWhitelist FRONT_DESK_ARRIVALS = new SortWhitelist(
            Map.of(
                    "reservationNumber", "reservationNumber",
                    "guestName", "guestName",
                    "checkInDate", "checkInDate",
                    "room", "room",
                    "source", "source",
                    "status", "status"),
            Sort.by(Sort.Order.asc("checkInDate"), Sort.Order.asc("reservationNumber")),
            List.of(Sort.Order.asc("reservationNumber")));

    /** Front Desk Departures (Batch 3A). See {@link #FRONT_DESK_ARRIVALS} for how this is used. */
    public static final SortWhitelist FRONT_DESK_DEPARTURES = new SortWhitelist(
            Map.of(
                    "reservationNumber", "reservationNumber",
                    "guestName", "guestName",
                    "plannedCheckOutDate", "plannedCheckOutDate",
                    "room", "room",
                    "nights", "nights",
                    "source", "source",
                    "status", "status",
                    "outstanding", "outstanding"),
            Sort.by(Sort.Order.asc("plannedCheckOutDate"), Sort.Order.asc("reservationNumber")),
            List.of(Sort.Order.asc("reservationNumber")));

    /** Front Desk In-house (Batch 3A). See {@link #FRONT_DESK_ARRIVALS} for how this is used. */
    public static final SortWhitelist FRONT_DESK_IN_HOUSE = new SortWhitelist(
            Map.of(
                    "reservationNumber", "reservationNumber",
                    "guestName", "guestName",
                    "room", "room",
                    "plannedCheckOutDate", "plannedCheckOutDate",
                    "checkedIn", "actualCheckInAt",
                    "nights", "nights",
                    "source", "source"),
            Sort.by(Sort.Order.asc("room"), Sort.Order.asc("reservationNumber")),
            List.of(Sort.Order.asc("reservationNumber")));

    private TableSorts() {}
}
