package com.example.hotel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AppUserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Verifies that browser-session principals retain the permission authority format used by APIs.
 */
class SessionUserDetailsServiceTest {

    /**
     * Maps active-user permissions to the {@code PERM_} authority prefix required by controllers.
     */
    @Test
    void shouldLoadActiveUserWithExistingPermissionAuthorityFormat() {
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        AppUser user = mock(AppUser.class);
        Role role = mock(Role.class);
        Permission permission = mock(Permission.class);
        UUID userId = UUID.randomUUID();

        when(appUserRepository.findByUsername("staff"))
                .thenReturn(Optional.of(user));
        when(user.isActive()).thenReturn(true);
        when(user.getId()).thenReturn(userId);
        when(user.getUsername()).thenReturn("staff");
        when(user.getPasswordHash()).thenReturn("password-hash");
        when(user.getRoles()).thenReturn(Set.of(role));
        when(role.getPermissions()).thenReturn(Set.of(permission));
        when(permission.getCode()).thenReturn("VIEW_BOOKING");

        SessionUserDetailsService service = new SessionUserDetailsService(appUserRepository);

        SessionUserPrincipal principal =
                (SessionUserPrincipal) service.loadUserByUsername("staff");

        assertEquals(userId, principal.id());
        assertEquals("staff", principal.getUsername());
        assertEquals("PERM_VIEW_BOOKING", principal.getAuthorities().iterator().next().getAuthority());
    }

    /**
     * Rejects inactive users before a browser session can be established.
     */
    @Test
    void shouldRejectInactiveUser() {
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        AppUser user = mock(AppUser.class);
        when(appUserRepository.findByUsername("inactive"))
                .thenReturn(Optional.of(user));
        when(user.isActive()).thenReturn(false);

        SessionUserDetailsService service = new SessionUserDetailsService(appUserRepository);

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("inactive"));
    }
}
