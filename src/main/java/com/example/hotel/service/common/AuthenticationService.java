package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.LoginRequest;
import com.example.hotel.dto.common.response.LoginResponse;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.entity.common.Permission;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.JwtService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticates active application users and issues access tokens with their permissions.
 */
@Service
public class AuthenticationService {

    private final AppUserRepository appUserRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the authentication service with the collaborators required to validate credentials
     * and issue tokens.
     *
     * @param appUserRepository repository used to find application users
     * @param jwtService service used to issue JWTs
     * @param passwordEncoder encoder used to verify submitted passwords
     */
    public AuthenticationService(
            AppUserRepository appUserRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticates an active user and issues an access token containing the user's permissions.
     *
     * @param request the submitted credentials
     * @return the issued access token
     * @throws ResponseStatusException if the credentials are invalid or the user is inactive
     */
    public LoginResponse authenticate(LoginRequest request) {
        AppUser user = appUserRepository
                .findByUsername(request.username())
                .filter(AppUser::isActive)
                .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .distinct()
                .toList();
        return new LoginResponse(jwtService.issue(user, permissions));
    }

    /**
     * Creates the uniform unauthorized response used for failed authentication attempts.
     *
     * @return an unauthorized response exception that does not disclose credential details
     */
    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}
