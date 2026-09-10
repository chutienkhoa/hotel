package com.example.hotel.config;

import com.example.hotel.security.JwtFilter;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
