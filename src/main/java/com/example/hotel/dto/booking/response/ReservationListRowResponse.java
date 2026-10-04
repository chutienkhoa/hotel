package com.example.hotel.dto.booking.response;

import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.ReservationStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies the fields of one Task33 Reservation List row. It is separate from {@link ReservationSummaryResponse},
 * which the Dashboard, REST and Check-out consumers keep using unchanged.
 *
 * @param id the reservation identifier
 * @param reservationNumber the immutable reservation number
 * @param guestFullName the primary guest's display name
 * @param guestCode the primary guest's code
 * @param checkInDate the planned check-in date
 * @param checkOutDate the planned check-out date
 * @param nights the planned number of nights ({@code checkOutDate - checkInDate}) in every Reservation state
 * @param rooms the operational rooms: booked rooms before check-in, current rooms while checked in, and the final
 *     rooms at check-out
 * @param source the booking source
 * @param otaBookingReference the external OTA booking reference, or {@code null} when absent
 * @param status the Reservation status
 */
public record ReservationListRowResponse(
        UUID id,
        String reservationNumber,
        String guestFullName,
        String guestCode,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        long nights,
        List<ReservationListRoomResponse> rooms,
        BookingSource source,
        String otaBookingReference,
        ReservationStatus status) {
}
