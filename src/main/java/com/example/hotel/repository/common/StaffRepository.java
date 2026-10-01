package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Staff;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /**
     * Finds the Staff member linked to a user account, if any.
     *
     * @param appUserId user account identifier
     * @return the linked Staff member, when one exists
     */
    Optional<Staff> findByAppUserId(UUID appUserId);

    /**
     * Retrieves every Staff member not linked to any user account, in Staff Code order.
     *
     * @return unlinked Staff members ordered by Staff Code
     */
    List<Staff> findByAppUserIdIsNullOrderByStaffCodeAsc();

    /**
     * Retrieves every Staff member that is linked to one of the supplied user accounts.
     *
     * @param appUserIds user account identifiers
     * @return Staff members linked to those accounts
     */
    List<Staff> findByAppUserIdIn(Collection<UUID> appUserIds);
}
