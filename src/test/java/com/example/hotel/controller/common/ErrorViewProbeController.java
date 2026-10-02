package com.example.hotel.controller.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Test-only controller used solely by {@link ErrorPageTest} to render the Task33 Batch 1B error
 * templates ({@code error/403}, {@code error/404}, {@code error/500}) directly, bypassing the real
 * container-level error dispatch that MockMvc cannot simulate. Lives under {@code src/test/java} and is
 * never part of the production {@code @ComponentScan}.
 */
@Controller
public class ErrorViewProbeController {

    @GetMapping("/test-only/error-403")
    public String error403() {
        return "error/403";
    }

    @GetMapping("/test-only/error-404")
    public String error404() {
        return "error/404";
    }

    @GetMapping("/test-only/error-500")
    public String error500() {
        return "error/500";
    }
}
