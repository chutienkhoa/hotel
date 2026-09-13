package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Expense;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence, stable listing, and lifecycle locking for Expense v1 records. */
public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
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
}
