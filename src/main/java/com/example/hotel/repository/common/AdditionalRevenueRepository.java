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

    /**
     * Sums Additional Revenue with a status and a revenue date inside a half-open date range.
     *
     * @param status the recognized status ({@code RECORDED})
     * @param startInclusive first date (inclusive)
     * @param endExclusive end date (exclusive)
     * @return the sum, or {@code null} when no row matches
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT SUM(r.amount) FROM AdditionalRevenue r "
                    + "WHERE r.status = :status AND r.revenueDate >= :startInclusive AND r.revenueDate < :endExclusive")
    java.math.BigDecimal sumAmountByStatusWithin(
            @org.springframework.data.repository.query.Param("status")
                    com.example.hotel.entity.common.AdditionalRevenueStatus status,
            @org.springframework.data.repository.query.Param("startInclusive") java.time.LocalDate startInclusive,
            @org.springframework.data.repository.query.Param("endExclusive") java.time.LocalDate endExclusive);

    /**
     * Sums the amounts of one status per category within {@code [startInclusive, endExclusive)}, ordered by
     * total descending then category code ascending.
     *
     * @param status status to include, RECORDED for recognized revenue
     * @param startInclusive first revenue date included
     * @param endExclusive first revenue date excluded
     * @return one row per category with revenue in the period
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT new com.example.hotel.repository.common.AdditionalRevenueCategoryTotalRow("
                    + "c.id, c.code, c.name, SUM(r.amount)) "
                    + "FROM AdditionalRevenue r JOIN r.category c "
                    + "WHERE r.status = :status AND r.revenueDate >= :startInclusive AND r.revenueDate < :endExclusive "
                    + "GROUP BY c.id, c.code, c.name "
                    + "ORDER BY SUM(r.amount) DESC, c.code ASC")
    java.util.List<AdditionalRevenueCategoryTotalRow> sumAmountByCategoryWithin(
            @org.springframework.data.repository.query.Param("status")
                    com.example.hotel.entity.common.AdditionalRevenueStatus status,
            @org.springframework.data.repository.query.Param("startInclusive") java.time.LocalDate startInclusive,
            @org.springframework.data.repository.query.Param("endExclusive") java.time.LocalDate endExclusive);

    /**
     * Loads the revenue rows linked to any of the given Charges in one query (reconciliation).
     *
     * @param chargeIds Charge identifiers
     * @return linked revenue rows
     */
    @Query("SELECT r FROM AdditionalRevenue r JOIN FETCH r.charge WHERE r.charge.id IN :chargeIds")
    java.util.List<AdditionalRevenue> findByChargeIdIn(
            @org.springframework.data.repository.query.Param("chargeIds") java.util.Collection<java.util.UUID> chargeIds);
}
