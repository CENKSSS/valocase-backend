package com.cenk.valocase.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple liveness endpoint used to confirm the backend is running.
 * Not part of any feature domain and not protected by auth.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "OK",
                "service", "valocase-backend"
        );
    }
}
