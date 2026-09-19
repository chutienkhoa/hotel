package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Permission;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides read access to the seeded permission catalogue. */
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    /**
     * Finds permissions by code.
     *
     * @param codes permission codes
     * @return the matching permissions
     */
    List<Permission> findByCodeIn(Collection<String> codes);
}
