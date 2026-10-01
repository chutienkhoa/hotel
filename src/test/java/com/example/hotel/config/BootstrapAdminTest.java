package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.common.validation.PasswordPolicy;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Role;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.RoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies the initial administrator bootstrap: the configured password must satisfy the same
 * {@link PasswordPolicy} as every other account, a violating configuration aborts startup instead
 * of silently creating a weak ADMIN or falling back to anything, and no rejection message
 * reproduces credential material.
 */
class BootstrapAdminTest {

    private static final String COMPLIANT_PASSWORD = "bootstrap-admin-compliant-password";

    /** Confirms a compliant configuration creates exactly one hashed ADMIN account. */
    @Test
    void shouldCreateAdministratorWithHashedPasswordWhenConfigurationIsCompliant() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        Role adminRole = mock(Role.class);
        when(users.findByUsernameIgnoreCase("hotel.admin")).thenReturn(Optional.empty());
        when(roles.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));

        runner(users, roles, "hotel.admin", COMPLIANT_PASSWORD, encoder).run();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        AppUser created = captor.getValue();
        assertEquals("hotel.admin", created.getUsername());
        assertFalse(COMPLIANT_PASSWORD.equals(created.getPasswordHash()), "the password must never be stored as-is");
        assertTrue(encoder.matches(COMPLIANT_PASSWORD, created.getPasswordHash()));
    }

    /** Confirms an existing administrator username is never re-created or re-hashed. */
    @Test
    void shouldNotRecreateAnExistingAdministrator() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        when(users.findByUsernameIgnoreCase("hotel.admin"))
                .thenReturn(Optional.of(new AppUser(UUID.randomUUID(), "hotel.admin", "existing-hash")));

        runner(users, roles, "hotel.admin", COMPLIANT_PASSWORD, new BCryptPasswordEncoder()).run();

        verify(users, never()).save(any(AppUser.class));
    }

    /**
     * Confirms a configured password that violates the application password policy aborts startup.
     * The bootstrap account is a full ADMIN, so the alternative — accepting it, or substituting a
     * fallback — would make it the weakest credential in the system.
     */
    @Test
    void shouldRejectABootstrapPasswordThatViolatesTheApplicationPasswordPolicy() {
        for (String weak : new String[] {"short", "1234567", "a".repeat(PasswordPolicy.MAX_LENGTH + 1)}) {
            AppUserRepository users = mock(AppUserRepository.class);
            RoleRepository roles = mock(RoleRepository.class);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> runner(users, roles, "hotel.admin", weak, new BCryptPasswordEncoder()).run(),
                    weak.length() + "-character password must be rejected");

            assertTrue(exception.getMessage().contains(PasswordPolicy.requirementDescription()));
            assertFalse(exception.getMessage().contains(weak), "the rejection must never echo the configured value");
            verify(users, never()).save(any(AppUser.class));
        }
    }

    /**
     * Confirms a policy violation is reported even when the administrator already exists, so a
     * weak configured credential cannot hide behind an account created by an earlier release.
     */
    @Test
    void shouldRejectAWeakPasswordEvenWhenTheAdministratorAlreadyExists() {
        AppUserRepository users = mock(AppUserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);

        assertThrows(
                IllegalStateException.class,
                () -> runner(users, roles, "hotel.admin", "weak", new BCryptPasswordEncoder()).run());

        verify(users, never()).findByUsernameIgnoreCase(any());
    }

    /**
     * Confirms the existing behavior for a deliberately empty configuration is unchanged: no
     * administrator is bootstrapped, and no weaker credential is substituted. Production supplies
     * no default for either property, so a deployment that configures neither still fails to start
     * before this runner is ever reached.
     */
    @Test
    void shouldSkipBootstrappingWhenUsernameOrPasswordIsBlank() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);

        runner(users, roles, "  ", COMPLIANT_PASSWORD, new BCryptPasswordEncoder()).run();
        runner(users, roles, "hotel.admin", "   ", new BCryptPasswordEncoder()).run();

        verify(users, never()).save(any(AppUser.class));
        verify(users, never()).findByUsernameIgnoreCase(any());
    }

    /**
     * Builds the bootstrap runner exactly as the Spring context would.
     *
     * @param users application user repository
     * @param roles role repository
     * @param username configured bootstrap administrator username
     * @param password configured bootstrap administrator password
     * @param encoder password encoder
     * @return the configured runner
     */
    private CommandLineRunner runner(
            AppUserRepository users,
            RoleRepository roles,
            String username,
            String password,
            PasswordEncoder encoder) {
        return new BootstrapAdmin().createBootstrapAdmin(users, roles, username, password, encoder);
    }
}
