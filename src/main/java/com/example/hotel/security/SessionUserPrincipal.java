package com.example.hotel.security;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Represents an authenticated browser user stored in the Spring Security session.
 */
public record SessionUserPrincipal(
        UUID id,
        String username,
        String passwordHash,
        Collection<? extends GrantedAuthority> authorities)
        implements UserDetails {

    /**
     * Returns the authorities used by existing backend authorization checks.
     *
     * @return the user's permission authorities
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * Returns the password hash used by Spring Security during form authentication.
     *
     * @return the stored password hash
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * Returns the authenticated user's login name.
     *
     * @return the user's login name
     */
    @Override
    public String getUsername() {
        return username;
    }

    /**
     * Indicates that the authenticated user account is usable by the session.
     *
     * @return {@code true}
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Indicates that the authenticated user account is not locked.
     *
     * @return {@code true}
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * Indicates that the authenticated user's credentials are current.
     *
     * @return {@code true}
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Indicates that only active application users can establish a browser session.
     *
     * @return {@code true}
     */
    @Override
    public boolean isEnabled() {
        return true;
    }
}
