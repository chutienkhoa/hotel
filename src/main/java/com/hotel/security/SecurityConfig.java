package com.hotel.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cấu hình Spring Security và chuỗi lọc JWT không trạng thái. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  /**
   * Tạo security filter chain cho API.
   *
   * @param http builder cấu hình HTTP security
   * @param filter bộ lọc JWT
   * @return filter chain đã cấu hình
   * @throws Exception nếu Spring Security không thể tạo filter chain
   */
  @Bean
  SecurityFilterChain chain(HttpSecurity http, JwtFilter filter) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/api/auth/login").permitAll().anyRequest().authenticated())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}

/** Đọc JWT từ header Authorization và thiết lập principal cho request hợp lệ. */
@Component
class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;

  /**
   * Tạo bộ lọc với dịch vụ JWT.
   *
   * @param jwt dịch vụ xác thực JWT
   */
  JwtFilter(JwtService jwt) {
    this.jwt = jwt;
  }

  @Override
  /**
   * Xác thực JWT bearer nếu có và tiếp tục chuỗi lọc.
   *
   * @param request HTTP request hiện tại
   * @param response HTTP response hiện tại
   * @param chain chuỗi filter còn lại
   * @throws ServletException nếu filter gặp lỗi servlet
   * @throws IOException nếu phản hồi không thể ghi
   */
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String h = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (h != null && h.startsWith("Bearer "))
      try {
        Claims c = jwt.parse(h.substring(7));
        List<?> ps = c.get("permissions", List.class);
        var authorities =
            ps.stream()
                .map(Object::toString)
                .map(p -> new SimpleGrantedAuthority("PERM_" + p))
                .toList();
        var principal =
            new CurrentUser(UUID.fromString(c.getSubject()), c.get("username", String.class));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(auth);
      } catch (Exception ignored) {
      }
    chain.doFilter(request, response);
  }
}
