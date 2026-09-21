package com.example.hotel.mapper.common;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.dto.common.response.AdditionalRevenueResponse;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import org.springframework.stereotype.Component;

/** Converts Additional Revenue entities and category data into MVC-safe responses. */
@Component
public class AdditionalRevenueMapper {

    public AdditionalRevenueResponse toResponse(AdditionalRevenue revenue) {
        return new AdditionalRevenueResponse(
                revenue.getId(),
                toCategoryResponse(revenue.getCategory()),
                revenue.getAmount(),
                revenue.getCurrency(),
                revenue.getRevenueDate(),
                revenue.getPaymentMethod() == null ? null : revenue.getPaymentMethod().name(),
                revenue.getDescription(),
                revenue.getStatus().name(),
                revenue.getVoidReason(),
                revenue.getVoidedAt(),
                revenue.getVoidedBy(),
                revenue.isChargeLinked());
    }

    public AdditionalRevenueCategoryResponse toCategoryResponse(AdditionalRevenueCategory category) {
        return new AdditionalRevenueCategoryResponse(
                category.getId(), category.getCode(), category.getName(), category.getDescription(), category.isActive());
    }
}
