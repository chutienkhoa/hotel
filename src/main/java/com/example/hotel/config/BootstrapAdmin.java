package com.example.hotel.config;

import com.example.hotel.common.validation.PasswordPolicy;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.repository.common.RoleRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Tạo tài khoản quản trị ban đầu khi cấu hình bootstrap được cung cấp. */
@Component
public class BootstrapAdmin {

    /**
     * Creates a runner that adds an administrator only when bootstrap credentials are configured
     * and the username does not already exist.
     *
     * <p>The configured password must satisfy the same {@link PasswordPolicy} that User Management
     * enforces for every other account. A configured password that violates it aborts startup
     * instead of being accepted, weakened, or replaced by a fallback: the bootstrap account is a
     * full ADMIN, so a weak value here would be the weakest credential in the system. The
     * username/password properties themselves have no default in {@code application.yml}, so a
     * deployment that supplies neither still fails to start. Validation happens before the
     * existence lookup, so a violating configuration cannot stay hidden behind an account that
     * was created earlier.</p>
     *
     * @param users repository used to access application users
     * @param roles repository used to access roles
     * @param username configured bootstrap administrator username
     * @param password configured bootstrap administrator password
     * @param passwordEncoder encoder used to hash the bootstrap password
     * @return the runner that performs conditional administrator bootstrapping
     */
    @Bean
    CommandLineRunner createBootstrapAdmin(
            AppUserRepository users,
            RoleRepository roles,
            @Value("${security.bootstrap-admin-username}") String username,
            @Value("${security.bootstrap-admin-password}") String password,
            PasswordEncoder passwordEncoder) {
        return arguments -> {
            if (username.isBlank() || password.isBlank()) {
                return;
            }
            requireCompliantBootstrapPassword(password);
            if (users.findByUsernameIgnoreCase(username).isEmpty()) {
                AppUser user = new AppUser(
                        UUID.randomUUID(),
                        username,
                        passwordEncoder.encode(password));
                user.audit(user.getId());
                user.addRole(roles.findByCode("ADMIN").orElseThrow());
                users.save(user);
            }
        };
    }

    /**
     * Rejects a configured bootstrap administrator password that does not satisfy the shared
     * application password policy. The rejection message states the requirement only and never
     * reproduces or describes the configured value.
     *
     * @param password configured bootstrap administrator password
     * @throws IllegalStateException if the configured password violates the application policy
     */
    private void requireCompliantBootstrapPassword(String password) {
        if (!PasswordPolicy.isAcceptable(password)) {
            throw new IllegalStateException(
                    "The configured bootstrap administrator password does not satisfy the application password "
                            + "policy. " + PasswordPolicy.requirementDescription()
                            + " Configure a compliant BOOTSTRAP_ADMIN_PASSWORD; the application does not fall back "
                            + "to a weaker credential.");
        }
    }
}
