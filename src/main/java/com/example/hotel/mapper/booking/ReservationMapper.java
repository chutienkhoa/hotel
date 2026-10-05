package com.example.hotel.mapper.booking;

import com.example.hotel.entity.booking.ReservationGuest;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationListRoomResponse;
import com.example.hotel.dto.booking.response.ReservationListRowResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.customer.Guest;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Converts reservation entities into the response DTO used by reservation endpoints.
 */
@Component
public class ReservationMapper {

    /**
     * Converts a reservation entity into its external response representation.
     *
     * @param reservation the reservation entity to convert
     * @return the response DTO containing reservation details
     */
    public Response toResponse(Reservation reservation) {
        return new Response(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getStatus().name(),
                reservation.getTotalAmount(),
                reservation.getCurrency());
    }

    /**
     * Converts a reservation entity into the compact representation used by list views, with its
     * assigned room numbers supplied separately to avoid an N+1 collection fetch.
     *
     * @param reservation the reservation entity to convert
     * @param roomNumbers the reservation's assigned room numbers, pre-joined for compact display
     * @return the reservation list representation
     */
    public ReservationSummaryResponse toSummaryResponse(Reservation reservation, String roomNumbers) {
        return new ReservationSummaryResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                guestFullName(reservation.getGuest()),
                roomNumbers,
                reservation.getStatus().name(),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                reservation.getTotalAmount(),
                reservation.getCurrency());
    }

    /**
     * Converts a reservation entity into one Task33 Reservation List row. Nights is the planned stay duration for every
     * Reservation state, and the rooms are supplied separately (batch-loaded) to avoid an N+1 lookup.
     *
     * @param reservation the reservation entity to convert
     * @param rooms the operational rooms already resolved for the reservation's status
     * @return the Reservation List row
     */
    public ReservationListRowResponse toListRow(Reservation reservation, List<ReservationListRoomResponse> rooms) {
        Guest guest = reservation.getGuest();
        return new ReservationListRowResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                guestFullName(guest),
                guest == null ? "" : guest.getGuestCode(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                ChronoUnit.DAYS.between(reservation.getCheckInDate(), reservation.getCheckOutDate()),
                rooms,
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getStatus());
    }

    /**
     * Maps a Reservation to its detail representation without the "last updated by" username, for callers that do not
     * present it.
     *
     * @param reservation the Reservation to map
     * @param accompanyingGuests the Accompanying Guests
     * @param effectiveContactName the effective Booking Contact name
     * @param effectiveContactPhone the effective Booking Contact phone
     * @param effectiveContactEmail the effective Booking Contact email
     * @param bookingContactFromPrimaryGuest whether the effective contact is the Primary Guest fallback
     * @param createdByUsername the creator's username, or {@code null}
     * @return the reservation detail representation
     */
    public ReservationDetailResponse toDetailResponse(
            Reservation reservation,
            List<AccompanyingGuestResponse> accompanyingGuests,
            String effectiveContactName,
            String effectiveContactPhone,
            String effectiveContactEmail,
            boolean bookingContactFromPrimaryGuest,
            String createdByUsername) {
        return toDetailResponse(
                reservation,
                accompanyingGuests,
                effectiveContactName,
                effectiveContactPhone,
                effectiveContactEmail,
                bookingContactFromPrimaryGuest,
                createdByUsername,
                null);
    }

    /**
     * Converts a reservation entity and its assigned rooms into the detail representation.
     *
     * @param reservation the reservation entity to convert
     * @param accompanyingGuests the reservation's Accompanying Guests
     * @param effectiveContactName the effective Booking Contact name already resolved by the caller (the
     *     Reservation's own snapshot, or the Primary Guest fallback)
     * @param effectiveContactPhone the effective Booking Contact phone already resolved by the caller
     * @param effectiveContactEmail the effective Booking Contact email already resolved by the caller
     * @param bookingContactFromPrimaryGuest whether the three effective values above came from the Primary Guest
     *     fallback rather than the Reservation's own Booking Contact snapshot
     * @param createdByUsername the username of the Reservation's creator, already resolved by the caller from the
     *     audit {@code createdBy} identifier, or {@code null} when it cannot be resolved
     * @param updatedByUsername the username of the user who most recently changed the Reservation, resolved from the
     *     audit {@code updatedBy} identifier, or {@code null} when it cannot be resolved
     * @return the reservation detail representation
     */
    public ReservationDetailResponse toDetailResponse(
            Reservation reservation,
            List<AccompanyingGuestResponse> accompanyingGuests,
            String effectiveContactName,
            String effectiveContactPhone,
            String effectiveContactEmail,
            boolean bookingContactFromPrimaryGuest,
            String createdByUsername,
            String updatedByUsername) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getGuest().getId(),
                reservation.getGuest().getGuestCode(),
                reservation.getStatus().name(),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                reservation.getAdultCount(),
                reservation.getChildCount(),
                reservation.getTotalAmount(),
                reservation.getCurrency(),
                reservation.getNotes(),
                reservation.getRooms().stream().map(this::toRoomResponse).toList(),
                accompanyingGuests,
                effectiveContactName,
                effectiveContactPhone,
                effectiveContactEmail,
                bookingContactFromPrimaryGuest,
                reservation.getCancellationReasonCode(),
                reservation.getCancellationReasonDetail(),
                reservation.getNoShowReason(),
                reservation.getReservedAt(),
                createdByUsername,
                reservation.getUpdatedAt(),
                updatedByUsername);
    }

    /**
     * Maps an Accompanying Guest association to its response (identity only; no Guest data is copied).
     *
     * @param link the association whose Guest is initialized
     * @return the response
     */
    public AccompanyingGuestResponse toAccompanyingGuestResponse(ReservationGuest link) {
        Guest accompanying = link.getGuest();
        return new AccompanyingGuestResponse(accompanying.getId(), accompanying.getGuestCode());
    }

    /** Maps a draft Reservation into the complete editable MVC representation. */
    public ReservationEditResponse toEditResponse(Reservation reservation, List<UUID> accompanyingGuestIds) {
        return new ReservationEditResponse(
                reservation.getId(),
                reservation.getStatus().name(),
                reservation.getGuest().getId(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                reservation.getAdultCount(),
                reservation.getChildCount(),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getCurrency(),
                reservation.getNotes(),
                reservation.getRooms().stream().map(this::toRoomResponse).toList(),
                accompanyingGuestIds,
                reservation.getBookingContactName(),
                reservation.getBookingContactPhone(),
                reservation.getBookingContactEmail());
    }

    /**
     * Joins the optional Guest name components for presentation without changing Guest data.
     *
     * @param guest Guest whose name is displayed
     * @return the trimmed display name, or an empty string when no name component is present or
     *     no Guest is associated
     */
    private String guestFullName(Guest guest) {
        if (guest == null) {
            return "";
        }
        return Stream.of(guest.getFirstName(), guest.getLastName())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
    }

    /**
     * Converts an assigned-room entity into the reservation room response DTO.
     *
     * @param reservationRoom the assigned-room entity to convert
     * @return the assigned-room response DTO
     */
    private ReservationRoomResponse toRoomResponse(ReservationRoom reservationRoom) {
        return new ReservationRoomResponse(
                reservationRoom.getRoom().getId(),
                reservationRoom.getRoom().getRoomNumber(),
                reservationRoom.getCheckInDate(),
                reservationRoom.getCheckOutDate(),
                reservationRoom.getNightlyRate(),
                reservationRoom.getTotalAmount(),
                reservationRoom.getRoom().getRoomType() == null
                        ? null
                        : reservationRoom.getRoom().getRoomType().getName(),
                reservationRoom.getRoom().getRoomType() == null
                        ? null
                        : reservationRoom.getRoom().getRoomType().getCapacity());
    }
}
