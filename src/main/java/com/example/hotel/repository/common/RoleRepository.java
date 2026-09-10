package com.example.hotel.repository.common;

import com.example.hotel.entity.common.Role;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ và tìm kiếm role. */
public interface RoleRepository extends JpaRepository<Role, UUID> {
  /**
   * Tìm role theo mã.
   *
   * @param code mã role
   * @return role nếu tồn tại
   */
  Optional<Role> findByCode(String code);
}
