package com.example.hotel.dto.common.response;

import java.util.List;

/**
 * Describes eligible Room Revenue that was excluded from the VND totals because its Reservation is not
 * in VND. The excluded amounts are never converted (Payment exchange rates are settlement data).
 *
 * @param reservationCount number of distinct Reservations excluded
 * @param reservationRoomCount number of ReservationRoom rows excluded
 * @param currencies sorted, distinct ISO currency codes of the excluded rows
 */
public record NonVndRoomRevenueWarning(int reservationCount, int reservationRoomCount, List<String> currencies) {}
