package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Staff;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Provides persistence access to Staff Management data. */
public interface StaffRepository extends JpaRepository<Staff, UUID>, JpaSpecificationExecutor<Staff> {

    /**
     * Determines whether a Staff Code is already assigned to an existing Staff member.
     *
     * @param staffCode generated Staff Code to check
     * @return {@code true} when the code is already in use
     */
    boolean existsByStaffCode(String staffCode);

    /**
     * Retrieves the next numeric value from the PostgreSQL Staff Code sequence.
     *
     * @return the next sequence number
     */
    @Query(value = "SELECT nextval('staff_code_sequence')", nativeQuery = true)
    long nextStaffCodeSequence();

    /**
     * Retrieves every active Staff member in deterministic Staff Code order, for the Daily Work
     * Record entry screen.
     *
     * @return active Staff members ordered by Staff Code
     */
    List<Staff> findByActiveTrueOrderByStaffCodeAsc();
}
