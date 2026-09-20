package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Expense;
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

/** Provides persistence, stable listing, filtered pagination, and lifecycle locking for Expense v1 records. */
public interface ExpenseRepository extends JpaRepository<Expense, UUID>, JpaSpecificationExecutor<Expense> {

    /**
     * Loads a filtered, paginated Expense list eagerly fetching its category to avoid N+1 lookups.
     *
     * @param spec optional filter predicate combination
     * @param pageable requested page, size, and sort
     * @return the matching Expense page with categories already fetched
     */
    @EntityGraph(attributePaths = "category")
    @Override
    Page<Expense> findAll(Specification<Expense> spec, Pageable pageable);

    /**
     * Counts all Expenses grouped by their current lifecycle status.
     *
     * @return status and count rows in stable status order
     */
    @Query("SELECT e.status, COUNT(e) FROM Expense e GROUP BY e.status ORDER BY e.status")
    List<Object[]> countAllByStatus();


    /**
     * Lists Expenses in stable descending business-date order.
     *
     * @return Expenses ordered by expense date and identifier
     */
    List<Expense> findAllByOrderByExpenseDateDescIdDesc();

    /**
     * Locks an Expense before updating draft fields or applying a lifecycle transition.
     *
     * @param id Expense identifier
     * @return locked Expense when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Expense e WHERE e.id = :id")
    Optional<Expense> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Sums Expenses with a status and an expense date inside a half-open date range.
     *
     * @param status the recognized status ({@code POSTED})
     * @param startInclusive first date (inclusive)
     * @param endExclusive end date (exclusive)
     * @return the sum, or {@code null} when no row matches
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT SUM(e.amount) FROM Expense e "
                    + "WHERE e.status = :status AND e.expenseDate >= :startInclusive AND e.expenseDate < :endExclusive")
    java.math.BigDecimal sumAmountByStatusWithin(
            @org.springframework.data.repository.query.Param("status")
                    com.example.hotel.entity.common.ExpenseStatus status,
            @org.springframework.data.repository.query.Param("startInclusive") java.time.LocalDate startInclusive,
            @org.springframework.data.repository.query.Param("endExclusive") java.time.LocalDate endExclusive);
}
