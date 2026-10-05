package com.example.hotel.service.booking;

import java.math.BigDecimal;

/**
 * Splits a Stay's Total Charges for the Reservation Detail Financial Summary.
 *
 * @param roomCharges sum of ACTIVE ROOM Charge amounts (accommodation from check-in and Stay Extension)
 * @param additionalCharges sum of ACTIVE non-ROOM Charge amounts (manually recorded charges)
 */
public record StayChargeBreakdown(BigDecimal roomCharges, BigDecimal additionalCharges) {}
