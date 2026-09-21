package com.example.hotel.dto.common.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Recorded Additional Revenue of one category in the month.
 *
 * @param categoryId category identifier
 * @param categoryCode stable category code, used as the tie-breaker
 * @param categoryName category name as stored (business data, not translated)
 * @param totalAmount sum of RECORDED amounts dated in the month
 * @param percentage share of the month's Additional Revenue with 2 decimals (HALF_UP), 0 when that total is 0
 */
public record AdditionalRevenueCategoryShare(
        UUID categoryId, String categoryCode, String categoryName, BigDecimal totalAmount, BigDecimal percentage) {}
