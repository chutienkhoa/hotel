package com.example.hotel.controller.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the session-based browser login page without exposing JWT credentials or tokens.
 */
@Controller
public class LoginPageController {

    /**
     * Returns the form-login page used by Spring Security browser authentication.
     *
     * @return the Thymeleaf login view name
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
