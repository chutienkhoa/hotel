package com.hotel.security;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ và tìm kiếm người dùng. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
  /**
   * Tìm người dùng theo tên đăng nhập.
   *
   * @param username tên đăng nhập
   * @return người dùng nếu tồn tại
   */
  Optional<AppUser> findByUsername(String username);
}
