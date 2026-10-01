package com.example.hotel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** Verifies JWT requests are revalidated against the current account on every request. */
class JwtFilterTest {

    private final JwtService jwtService =
            new JwtService("test-secret-test-secret-test-secret-test-secret", Duration.ofHours(1));
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final JwtFilter filter = new JwtFilter(jwtService, users);

    /** Clears the security context. */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a previously issued token stops authenticating once the account is deactivated. */
    @Test
    void shouldRejectTokenIssuedBeforeDeactivation() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_STAFF");
        String token = jwtService.issue(user, List.of("MANAGE_STAFF"));
        user.deactivate();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        run(token);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Confirms a token for a deleted or unknown account is rejected safely. */
    @Test
    void shouldRejectTokenForMissingAccount() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_STAFF");
        String token = jwtService.issue(user, List.of("MANAGE_STAFF"));
        when(users.findById(any())).thenReturn(Optional.empty());

        run(token);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Confirms authorities come from the current roles, never from the stale permission claim. */
    @Test
    void shouldDeriveAuthoritiesFromCurrentRolesNotTokenClaim() throws Exception {
        AppUser user = ActiveUserSessionFilterTest.user(true, "MANAGE_ROOM");
        String token = jwtService.issue(user, List.of("MANAGE_USER", "MANAGE_STAFF"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        run(token);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals(1, authentication.getAuthorities().size());
        assertEquals("PERM_MANAGE_ROOM", authentication.getAuthorities().iterator().next().getAuthority());
    }

    /** Confirms a malformed token never authenticates. */
    @Test
    void shouldRejectInvalidToken() throws Exception {
        run("not-a-jwt");

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
