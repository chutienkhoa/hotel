package com.example.hotel.service.booking;

import java.math.BigDecimal;

/**
 * Represents the calculated financial totals for one Stay.
 *
 * @param totalCharges sum of authoritative Charge amounts for the Stay
 * @param totalPaidPayments sum of PAID Payment amounts for the Stay
 * @param outstanding difference between total charges and total paid payments
 */
public record StayBalance(
        BigDecimal totalCharges, BigDecimal totalPaidPayments, BigDecimal outstanding) {}
