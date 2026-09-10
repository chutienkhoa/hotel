package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AppUser;
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
