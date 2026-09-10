package com.example.hotel.security;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.repository.common.AppUserRepository;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads active application users as session principals for Spring Security form login.
 */
@Service
public class SessionUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    /**
     * Creates the service with access to persisted application users.
     *
     * @param appUserRepository repository used to load application users
     */
    public SessionUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * Loads an active user and maps permissions to the authority names used by existing APIs.
     *
     * @param username the login name supplied to the form-login flow
     * @return the session principal for the active user
     * @throws UsernameNotFoundException if no active user has the supplied login name
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = appUserRepository
                .findByUsername(username)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .distinct()
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
        return new SessionUserPrincipal(
                user.getId(), user.getUsername(), user.getPasswordHash(), authorities);
    }
}
