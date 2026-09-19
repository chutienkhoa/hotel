package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Cung cấp thao tác lưu trữ và tìm kiếm role. */
public interface RoleRepository extends JpaRepository<Role, UUID> {
    /**
     * Tìm role theo mã.
     *
     * @param code mã role
     * @return role nếu tồn tại
     */
    Optional<Role> findByCode(String code);

    /**
     * Finds roles by code.
     *
     * @param codes role codes
     * @return the matching roles
     */
    List<Role> findByCodeIn(Collection<String> codes);

    /**
     * Locks the built-in role rows until the surrounding transaction ends, so concurrent
     * permission-matrix saves are serialized rather than raced.
     *
     * @return identifiers of the built-in roles
     */
    @Query(
            value = "SELECT r.id FROM role r WHERE r.code IN ('ADMIN', 'MANAGER', 'STAFF') "
                    + "ORDER BY r.id FOR UPDATE",
            nativeQuery = true)
    List<java.util.UUID> lockBuiltInRoleIds();
}
