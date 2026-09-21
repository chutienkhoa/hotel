package com.example.hotel.service.booking;

import java.math.BigDecimal;

/**
 * Represents the calculated financial totals for one Stay.
 *
 * @param totalCharges sum of authoritative Charge amounts for the Stay, in Reservation currency
 * @param totalPaidPayments sum of PAID Payment applied amounts for the Stay, in Reservation currency
 * @param outstanding difference between total charges and total paid payments
 */
public record StayBalance(
        BigDecimal totalCharges, BigDecimal totalPaidPayments, BigDecimal outstanding) {}
