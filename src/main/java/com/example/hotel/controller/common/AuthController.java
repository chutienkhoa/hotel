package com.example.hotel.controller.common;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.JwtService;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** Cung cấp API xác thực để nhận JWT. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AppUserRepository users;
  private final JwtService jwt;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  /**
   * Tạo controller với repository người dùng và dịch vụ JWT.
   *
   * @param users repository người dùng
   * @param jwt dịch vụ phát hành JWT
   */
  AuthController(AppUserRepository users, JwtService jwt) {
    this.users = users;
    this.jwt = jwt;
  }

  /** Dữ liệu đăng nhập chứa tên đăng nhập và mật khẩu. */
  record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  /** Dữ liệu phản hồi chứa access token đã phát hành. */
  record LoginResponse(String accessToken) {}

  /**
   * Xác thực người dùng và phát hành JWT cùng các permission hiện có.
   *
   * @param request thông tin đăng nhập
   * @return token truy cập đã phát hành
   */
  @PostMapping("/login")
  public LoginResponse login(@RequestBody @jakarta.validation.Valid LoginRequest request) {
    AppUser u =
        users
            .findByUsername(request.username())
            .filter(AppUser::isActive)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    if (!encoder.matches(request.password(), u.getPasswordHash()))
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Invalid credentials");
    var permissions =
        u.getRoles().stream()
            .flatMap(r -> r.getPermissions().stream())
            .map(Permission::getCode)
            .distinct()
            .toList();
    return new LoginResponse(jwt.issue(u, permissions));
  }
}
