package com.example.hotel.mapper.booking;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationEditResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.customer.Guest;
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
     * Converts a reservation entity and its assigned rooms into the detail representation.
     *
     * @param reservation the reservation entity to convert
     * @return the reservation detail representation
     */
    public ReservationDetailResponse toDetailResponse(Reservation reservation) {
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
                reservation.getTotalAmount(),
                reservation.getCurrency(),
                reservation.getNotes(),
                reservation.getRooms().stream().map(this::toRoomResponse).toList());
    }

    /** Maps a draft Reservation into the complete editable MVC representation. */
    public ReservationEditResponse toEditResponse(Reservation reservation) {
        return new ReservationEditResponse(
                reservation.getId(),
                reservation.getStatus().name(),
                reservation.getGuest().getId(),
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                reservation.getSource(),
                reservation.getOtaBookingReference(),
                reservation.getCurrency(),
                reservation.getNotes(),
                reservation.getRooms().stream().map(this::toRoomResponse).toList());
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
                reservationRoom.getTotalAmount());
    }
}
