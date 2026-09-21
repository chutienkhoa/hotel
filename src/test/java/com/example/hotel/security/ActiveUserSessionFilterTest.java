package com.example.hotel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AppUserRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Verifies an established browser session stops working as soon as its account is deactivated. */
class ActiveUserSessionFilterTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ActiveUserSessionFilter filter = new ActiveUserSessionFilter(users);

    /** Clears the security context. */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a deactivated account's session is invalidated, unauthenticated, and redirected to login. */
    @Test
    void shouldRejectAndInvalidateSessionOfDeactivatedUser() throws Exception {
        AppUser user = user(true, "MANAGE_STAFF");
        user.deactivate();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        MockHttpServletRequest request = authenticatedRequest(user);
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) request.getSession(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(session.isInvalid());
        assertEquals("/login", response.getRedirectedUrl());
        assertNull(chain.getRequest());
    }

    /** Confirms a session whose account no longer exists is rejected. */
    @Test
    void shouldRejectSessionOfMissingUser() throws Exception {
        AppUser user = user(true, "MANAGE_STAFF");
        when(users.findById(user.getId())).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest(user), response, chain);

        assertEquals("/login", response.getRedirectedUrl());
        assertNull(chain.getRequest());
    }

    /** Confirms an active account continues, and its authorities are refreshed from current roles. */
    @Test
    void shouldContinueAndRefreshAuthoritiesForActiveUser() throws Exception {
        AppUser current = user(true, "MANAGE_ROOM");
        when(users.findById(current.getId())).thenReturn(Optional.of(current));
        SessionUserPrincipal stale = new SessionUserPrincipal(
                current.getId(), "an.le", "hash", List.of(new SimpleGrantedAuthority("PERM_MANAGE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(stale, null, stale.getAuthorities()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("PERM_MANAGE_ROOM", authorities.iterator().next().getAuthority());
    }

    /** Confirms unauthenticated requests pass through without any account lookup. */
    @Test
    void shouldIgnoreUnauthenticatedRequests() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    private MockHttpServletRequest authenticatedRequest(AppUser user) {
        SessionUserPrincipal principal = new SessionUserPrincipal(
                user.getId(), user.getUsername(), user.getPasswordHash(), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new org.springframework.mock.web.MockHttpSession());
        return request;
    }

    static AppUser user(boolean active, String permissionCode) throws Exception {
        Constructor<Permission> permissionConstructor = Permission.class.getDeclaredConstructor();
        permissionConstructor.setAccessible(true);
        Permission permission = permissionConstructor.newInstance();
        set(permission, "code", permissionCode);
        Constructor<Role> roleConstructor = Role.class.getDeclaredConstructor();
        roleConstructor.setAccessible(true);
        Role role = roleConstructor.newInstance();
        set(role, "code", "STAFF");
        role.getPermissions().addAll(Set.of(permission));
        AppUser user = new AppUser(UUID.randomUUID(), "an.le", "hash");
        user.replaceRoles(role);
        return user;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
