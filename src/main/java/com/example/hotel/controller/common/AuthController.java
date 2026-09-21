package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.LoginRequest;
import com.example.hotel.dto.common.response.LoginResponse;
import com.example.hotel.service.common.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cung cấp API xác thực để nhận JWT. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationService authenticationService;

    /**
     * Creates the controller with the service that performs authentication.
     *
     * @param authenticationService service that validates credentials and issues access tokens
     */
    AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Authenticates the submitted credentials and returns an access token.
     *
     * @param request submitted login credentials
     * @return the issued access token
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.authenticate(request);
    }
}
