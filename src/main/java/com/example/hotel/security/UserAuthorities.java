package com.example.hotel.security;

import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Derives the permission authorities a user currently holds from the persisted role model. */
public final class UserAuthorities {

    private UserAuthorities() {}

    /**
     * Resolves the current {@code PERM_<code>} authorities of a user from their roles.
     *
     * @param user the persisted application user
     * @return the user's distinct permission authorities
     */
    public static List<SimpleGrantedAuthority> resolve(AppUser user) {
        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .distinct()
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
    }
}
