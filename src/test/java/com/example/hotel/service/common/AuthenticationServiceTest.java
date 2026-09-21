package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.LoginRequest;
import com.example.hotel.entity.common.AppUser;
import com.example.hotel.repository.common.AppUserRepository;
import com.example.hotel.security.JwtService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies API login: case-insensitive username, inactive rejection, and password verification. */
class AuthenticationServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthenticationService service = new AuthenticationService(
            users, new JwtService("test-secret-test-secret-test-secret-test-secret", Duration.ofHours(1)), encoder);

    /** Confirms login succeeds regardless of the submitted username's letter case. */
    @Test
    void shouldAuthenticateWithCaseInsensitiveUsername() {
        AppUser user = new AppUser(UUID.randomUUID(), "an.le", encoder.encode("password1"));
        when(users.findByUsernameIgnoreCase("An.Le")).thenReturn(Optional.of(user));

        assertNotNull(service.authenticate(new LoginRequest("An.Le", "password1")).accessToken());
    }

    /** Confirms an inactive account cannot obtain a token. */
    @Test
    void shouldRejectInactiveUser() {
        AppUser user = new AppUser(UUID.randomUUID(), "an.le", encoder.encode("password1"));
        user.deactivate();
        when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.authenticate(new LoginRequest("an.le", "password1")));
        assertEquals(401, exception.getStatusCode().value());
    }

    /** Confirms a wrong password is rejected uniformly. */
    @Test
    void shouldRejectWrongPassword() {
        AppUser user = new AppUser(UUID.randomUUID(), "an.le", encoder.encode("password1"));
        when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class,
                () -> service.authenticate(new LoginRequest("an.le", "wrong-password")));
    }
}
