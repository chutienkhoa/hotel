package com.example.hotel.config;

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
            if (!username.isBlank()
                    && !password.isBlank()
                    && users.findByUsernameIgnoreCase(username).isEmpty()) {
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
}
