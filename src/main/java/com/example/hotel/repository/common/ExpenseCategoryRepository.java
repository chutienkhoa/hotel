package com.example.hotel.repository.common;

import com.example.hotel.entity.common.ExpenseCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence access to configurable Expense v1 category reference data. */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    /**
     * Lists every Expense category, active or inactive, in stable code order.
     *
     * <p>Used where historical Expense data must remain representable, such as the Expense list
     * category filter (Task 2), which must still be able to filter by a category that has since
     * been deactivated.</p>
     *
     * @return ordered Expense category reference data, active and inactive
     */
    List<ExpenseCategory> findAllByOrderByCodeAsc();

    /**
     * Lists only active Expense categories in stable code order.
     *
     * <p>Used where a category must be newly selectable, such as the Create Expense form.</p>
     *
     * @return ordered active Expense category reference data
     */
    List<ExpenseCategory> findByActiveTrueOrderByCodeAsc();

    /**
     * Determines whether an Expense category already uses the supplied normalized code.
     *
     * @param code normalized candidate category code
     * @return {@code true} when the code is already in use
     */
    boolean existsByCode(String code);
}
