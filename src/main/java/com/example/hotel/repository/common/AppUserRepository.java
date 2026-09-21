package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Cung cấp thao tác lưu trữ và tìm kiếm người dùng. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {
    /**
     * Tìm người dùng theo tên đăng nhập.
     *
     * @param username tên đăng nhập
     * @return người dùng nếu tồn tại
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * Finds a user by login name, ignoring letter case.
     *
     * @param username the login name in any letter case
     * @return the matching user, when one exists
     */
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    /**
     * Determines whether a login name is already taken, ignoring letter case.
     *
     * @param username the login name in any letter case
     * @return {@code true} when a user already holds that login name
     */
    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Locks every currently active ADMIN account row until the surrounding transaction ends and
     * returns their identifiers. Concurrent mutations that could remove an ADMIN block on these
     * row locks, so the last-active-ADMIN check cannot be raced.
     *
     * @return identifiers of every active ADMIN user
     */
    @Query(
            value = "SELECT au.id FROM app_user au "
                    + "JOIN user_role ur ON ur.user_id = au.id "
                    + "JOIN role r ON r.id = ur.role_id "
                    + "WHERE r.code = 'ADMIN' AND au.active = TRUE "
                    + "ORDER BY au.id FOR UPDATE OF au",
            nativeQuery = true)
    List<UUID> lockActiveAdminIds();
}
