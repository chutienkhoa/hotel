package com.hotel.security;

import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Tạo tài khoản quản trị ban đầu khi cấu hình bootstrap được cung cấp. */
@Component
public class BootstrapAdmin {
  /**
   * Tạo command runner chỉ thêm admin khi tên, mật khẩu và người dùng chưa tồn tại.
   *
   * @param users repository người dùng
   * @param roles repository role
   * @param username tên admin bootstrap
   * @param password mật khẩu admin bootstrap
   * @return command runner thực hiện bootstrap
   */
  @Bean
  CommandLineRunner createBootstrapAdmin(
      AppUserRepository users,
      RoleRepository roles,
      @Value("${security.bootstrap-admin-username}") String username,
      @Value("${security.bootstrap-admin-password}") String password) {
    return args -> {
      if (!username.isBlank() && !password.isBlank() && users.findByUsername(username).isEmpty()) {
        AppUser user =
            new AppUser(UUID.randomUUID(), username, new BCryptPasswordEncoder().encode(password));
        user.audit(user.getId());
        user.addRole(roles.findByCode("ADMIN").orElseThrow());
        users.save(user);
      }
    };
  }
}
