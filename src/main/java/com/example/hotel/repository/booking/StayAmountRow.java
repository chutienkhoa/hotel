package com.example.hotel.repository.booking;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A summed monetary amount for one Stay, read in one grouped query for many Stays.
 *
 * @param stayId Stay identifier
 * @param amount summed amount
 */
public record StayAmountRow(UUID stayId, BigDecimal amount) {}
