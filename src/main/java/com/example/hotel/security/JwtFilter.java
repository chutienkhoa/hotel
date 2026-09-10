package com.example.hotel.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Đọc JWT từ header Authorization và thiết lập principal cho request hợp lệ. */
@Component
public class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;

  /**
   * Tạo bộ lọc với dịch vụ JWT.
   *
   * @param jwt dịch vụ xác thực JWT
   */
  public JwtFilter(JwtService jwt) {
    this.jwt = jwt;
  }

  /**
   * Xác thực JWT bearer nếu có và tiếp tục chuỗi lọc.
   *
   * @param request HTTP request hiện tại
   * @param response HTTP response hiện tại
   * @param chain chuỗi filter còn lại
   * @throws ServletException nếu filter gặp lỗi servlet
   * @throws IOException nếu phản hồi không thể ghi
   */
  @Override
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
