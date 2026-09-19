package com.example.hotel.security;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đọc JWT từ header Authorization và thiết lập principal cho request hợp lệ. The token only
 * identifies the account; the account must still exist and be active, and its authorities are
 * derived from its current roles rather than from any permission snapshot inside the token.
 */
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final AppUserRepository appUserRepository;

    /**
     * Tạo bộ lọc với dịch vụ JWT.
     *
     * @param jwt dịch vụ xác thực JWT
     * @param appUserRepository repository used to reload the current account
     */
    public JwtFilter(JwtService jwt, AppUserRepository appUserRepository) {
        this.jwt = jwt;
        this.appUserRepository = appUserRepository;
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
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            try {
                Claims claims = jwt.parse(authorizationHeader.substring(7));
                AppUser user = appUserRepository
                        .findById(UUID.fromString(claims.getSubject()))
                        .filter(AppUser::isActive)
                        .orElse(null);
                if (user != null) {
                    var principal = new CurrentUser(user.getId(), user.getUsername());
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, UserAuthorities.resolve(user));
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // Invalid tokens must not establish an authenticated security context.
            }
        }
        chain.doFilter(request, response);
    }
}
