package com.example.hotel.security;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalidates an already-authenticated browser session against the current user account on every
 * request. A deactivated or missing account loses its session immediately, and the session's
 * authorities are refreshed from the current role model so role changes apply on the next request.
 */
public class ActiveUserSessionFilter extends OncePerRequestFilter {

    private final AppUserRepository appUserRepository;

    /**
     * Creates the filter with access to persisted application users.
     *
     * @param appUserRepository repository used to reload the current account
     */
    public ActiveUserSessionFilter(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * Rejects the session of an inactive or missing account, otherwise refreshes its authorities.
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param chain remaining filter chain
     * @throws ServletException if the chain raises a servlet failure
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionUserPrincipal principal) {
            AppUser user = appUserRepository.findById(principal.id()).orElse(null);
            if (user == null || !user.isActive()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendRedirect(request.getContextPath() + "/login");
                return;
            }
            SessionUserPrincipal refreshed = new SessionUserPrincipal(
                    user.getId(), user.getUsername(), user.getPasswordHash(), UserAuthorities.resolve(user));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    refreshed, authentication.getCredentials(), refreshed.getAuthorities()));
        }
        chain.doFilter(request, response);
    }
}
