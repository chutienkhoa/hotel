package com.example.hotel.repository.common;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Narrow read projection of the recorded Additional Revenue total of one category.
 *
 * @param categoryId category identifier
 * @param categoryCode stable category code
 * @param categoryName category name as stored
 * @param totalAmount sum of the category's RECORDED amounts in the period
 */
public record AdditionalRevenueCategoryTotalRow(
        UUID categoryId, String categoryCode, String categoryName, BigDecimal totalAmount) {}
