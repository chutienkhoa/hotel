package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AdditionalRevenue;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence and locking for Additional Revenue v1 records. */
public interface AdditionalRevenueRepository
        extends JpaRepository<AdditionalRevenue, UUID>, JpaSpecificationExecutor<AdditionalRevenue> {

    @EntityGraph(attributePaths = "category")
    @Override
    Page<AdditionalRevenue> findAll(Specification<AdditionalRevenue> spec, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    List<AdditionalRevenue> findAllByOrderByRevenueDateDescIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT revenue FROM AdditionalRevenue revenue WHERE revenue.id = :id")
    Optional<AdditionalRevenue> findByIdForUpdate(@Param("id") UUID id);
}
