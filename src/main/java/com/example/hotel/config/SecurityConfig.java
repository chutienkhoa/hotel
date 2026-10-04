package com.example.hotel.config;

import com.example.hotel.controller.customer.GuestMultipartUploadFilter;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.ActiveUserSessionFilter;
import com.example.hotel.security.JwtFilter;
import com.example.hotel.security.JwtService;
import com.example.hotel.security.SessionUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * Configures separate stateless API security and session-backed Thymeleaf MVC security.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Creates the shared BCrypt password encoder used by API, bootstrap, and session login flows.
     *
     * @return the BCrypt password encoder
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Creates the JWT filter, which revalidates the account on every API request.
     *
     * @param jwtService service that parses and validates tokens
     * @param appUserRepository repository used to reload the current account
     * @return the JWT authentication filter
     */
    @Bean
    JwtFilter jwtFilter(JwtService jwtService, AppUserRepository appUserRepository) {
        return new JwtFilter(jwtService, appUserRepository);
    }

    /**
     * Creates the filter that revalidates browser sessions against the current account.
     *
     * @param appUserRepository repository used to reload the current account
     * @return the active-session filter
     */
    @Bean
    ActiveUserSessionFilter activeUserSessionFilter(AppUserRepository appUserRepository) {
        return new ActiveUserSessionFilter(appUserRepository);
    }

    /**
     * Creates the authentication provider used only by browser form login.
     *
     * @param sessionUserDetailsService service that loads session users and authorities
     * @param passwordEncoder encoder used to verify submitted passwords
     * @return the configured DAO authentication provider
     */
    @Bean
    DaoAuthenticationProvider sessionAuthenticationProvider(
            SessionUserDetailsService sessionUserDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(sessionUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Configures the existing JWT-protected API as a stateless security chain.
     *
     * @param http builder used to configure HTTP security
     * @param filter filter that establishes authentication from a JWT
     * @return the configured API security filter chain
     * @throws Exception if Spring Security cannot build the filter chain
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtFilter filter) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorization -> authorization
                        .requestMatchers("/api/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Configures browser routes to authenticate through a Spring Security session and form login.
     *
     * @param http builder used to configure HTTP security
     * @param sessionAuthenticationProvider provider used for browser form login
     * @param guestMultipartUploadFilter filter that safely recovers from an oversized Guest
     *     passport upload rejected while Spring Security reads the CSRF request parameter, before
     *     that failure would otherwise reach the servlet container's default error page
     * @param activeUserSessionFilter filter that rejects sessions of deactivated accounts
     * @return the configured MVC security filter chain
     * @throws Exception if Spring Security cannot build the filter chain
     */
    @Bean
    @Order(2)
    SecurityFilterChain mvcSecurityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider sessionAuthenticationProvider,
            GuestMultipartUploadFilter guestMultipartUploadFilter,
            ActiveUserSessionFilter activeUserSessionFilter)
            throws Exception {
        return http
                .securityMatcher("/**")
                .authenticationProvider(sessionAuthenticationProvider)
                .authorizeHttpRequests(authorization -> authorization
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(formLogin -> formLogin
                        .loginPage("/login")
                        .defaultSuccessUrl("/dashboard", true)
                        .permitAll())
                .addFilterBefore(guestMultipartUploadFilter, CsrfFilter.class)
                .addFilterAfter(activeUserSessionFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
