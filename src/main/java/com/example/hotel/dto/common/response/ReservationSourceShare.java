package com.example.hotel.dto.common.response;

import com.example.hotel.entity.booking.BookingSource;
import java.math.BigDecimal;

/**
 * Share of one booking source among the Reservations whose check-in date falls in the month (all statuses).
 *
 * @param source booking source
 * @param count number of Reservations from this source
 * @param percentage {@code count / total x 100} with 2 decimals (HALF_UP), 0 when there are no Reservations
 */
public record ReservationSourceShare(BookingSource source, long count, BigDecimal percentage) {}
