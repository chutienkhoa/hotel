package com.example.hotel.service.booking;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.customer.Guest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves the Booking Contact actually shown for a Reservation on operational read screens: its own Booking
 * Contact snapshot when any field of it is present, otherwise the CURRENT Primary Guest's name/phone/email as a
 * display-only fallback. The fallback is never persisted, never mutates the Reservation or the Guest, and is not
 * itself historical snapshot data — it only lets a Reservation created before Booking Contact existed still show
 * staff who to contact.
 *
 * @param name effective contact name, or {@code null} when neither the snapshot nor the Guest has one
 * @param phone effective contact phone, or {@code null} when neither the snapshot nor the Guest has one
 * @param email effective contact email, or {@code null} when neither the snapshot nor the Guest has one
 * @param fromPrimaryGuest {@code true} when no Booking Contact snapshot exists and these values come from the
 *     Primary Guest fallback instead
 */
public record EffectiveBookingContact(String name, String phone, String email, boolean fromPrimaryGuest) {

    /**
     * Resolves the effective Booking Contact of one Reservation.
     *
     * @param reservation Reservation whose Booking Contact snapshot (or Primary Guest fallback) is resolved; its
     *     Primary Guest association must already be accessible (loaded or joined) by the caller
     * @return the effective name/phone/email and whether they came from the Primary Guest fallback
     */
    public static EffectiveBookingContact of(Reservation reservation) {
        if (hasSnapshot(reservation)) {
            return new EffectiveBookingContact(
                    reservation.getBookingContactName(),
                    reservation.getBookingContactPhone(),
                    reservation.getBookingContactEmail(),
                    false);
        }
        Guest guest = reservation.getGuest();
        return new EffectiveBookingContact(guestFullName(guest), guest.getPhone(), guest.getEmail(), true);
    }

    /**
     * Joins a Guest's optional name components for display, matching the join rule already used elsewhere for
     * Guest display names (first name, then last name, blank components skipped).
     *
     * @param guest Guest whose name is displayed
     * @return the trimmed display name, or {@code null} when no name component is present
     */
    public static String guestFullName(Guest guest) {
        String joined = Stream.of(guest.getFirstName(), guest.getLastName())
                .filter(EffectiveBookingContact::isPresent)
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return joined.isBlank() ? null : joined;
    }

    private static boolean hasSnapshot(Reservation reservation) {
        return isPresent(reservation.getBookingContactName())
                || isPresent(reservation.getBookingContactPhone())
                || isPresent(reservation.getBookingContactEmail());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
