package com.example.hotel.security;

import com.example.hotel.entity.common.AppUser;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Phát hành và xác thực JWT cho người dùng ứng dụng. */
@Service
public class JwtService {
  private final SecretKey key;
  private final Duration expiration;

  /**
   * Khởi tạo dịch vụ JWT từ secret và thời hạn cấu hình.
   *
   * @param secret secret ký JWT
   * @param expiration thời hạn token
   */
  public JwtService(
      @Value("${security.jwt-secret}") String secret,
      @Value("${security.jwt-expiration}") Duration expiration) {
    key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expiration = expiration;
  }

  /**
   * Phát hành token cho người dùng với permission đã được tính.
   *
   * @param user người dùng nhận token
   * @param permissions permission được nhúng trong token
   * @return JWT đã ký
   */
  public String issue(AppUser user, Collection<String> permissions) {
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("username", user.getUsername())
        .claim("permissions", permissions)
        .expiration(Date.from(Instant.now().plus(expiration)))
        .signWith(key)
        .compact();
  }

  /**
   * Phân tích và xác thực token đã ký.
   *
   * @param token JWT cần xác thực
   * @return claims của token hợp lệ
   */
  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
