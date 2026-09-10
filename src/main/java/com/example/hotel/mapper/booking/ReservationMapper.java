package com.example.hotel.mapper.booking;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationRoomResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.entity.booking.ReservationRoom;
import com.example.hotel.entity.booking.Reservation;
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
     * Converts a reservation entity into the compact representation used by list views.
     *
     * @param reservation the reservation entity to convert
     * @return the reservation list representation
     */
    public ReservationSummaryResponse toSummaryResponse(Reservation reservation) {
        return new ReservationSummaryResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getStatus().name(),
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
                reservation.getCheckInDate(),
                reservation.getCheckOutDate(),
                reservation.getTotalAmount(),
                reservation.getCurrency(),
                reservation.getNotes(),
                reservation.getRooms().stream().map(this::toRoomResponse).toList());
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
