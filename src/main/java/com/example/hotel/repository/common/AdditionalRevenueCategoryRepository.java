package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AdditionalRevenueCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides reference-data lookup for Additional Revenue categories. */
public interface AdditionalRevenueCategoryRepository extends JpaRepository<AdditionalRevenueCategory, UUID> {

    boolean existsByCode(String code);

    List<AdditionalRevenueCategory> findByActiveTrueOrderByCodeAsc();

    /**
     * Finds a category by its stable code.
     *
     * @param code category code
     * @return the category, when it exists
     */
    java.util.Optional<AdditionalRevenueCategory> findByCode(String code);

    List<AdditionalRevenueCategory> findAllByOrderByCodeAsc();
}
