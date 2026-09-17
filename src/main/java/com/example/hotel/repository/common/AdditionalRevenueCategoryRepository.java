package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AdditionalRevenueCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides reference-data lookup for Additional Revenue categories. */
public interface AdditionalRevenueCategoryRepository extends JpaRepository<AdditionalRevenueCategory, UUID> {

    boolean existsByCode(String code);

    List<AdditionalRevenueCategory> findByActiveTrueOrderByCodeAsc();

    List<AdditionalRevenueCategory> findAllByOrderByCodeAsc();
}
