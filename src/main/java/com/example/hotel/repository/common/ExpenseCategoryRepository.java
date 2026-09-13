package com.example.hotel.repository.common;

import com.example.hotel.entity.common.ExpenseCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides read-only persistence access to Expense v1 category reference data. */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    /**
     * Lists approved Expense categories in stable code order.
     *
     * @return ordered Expense category reference data
     */
    List<ExpenseCategory> findAllByOrderByCodeAsc();
}
